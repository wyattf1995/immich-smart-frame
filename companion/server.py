#!/usr/bin/env python3
"""Small, authenticated control plane. Never executes shell or proxies arbitrary URLs."""
import base64
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

MODES = ('photos', 'home', 'weather', 'birds', 'cameras', 'calendar')
DEFAULTS = {'modeOrder': list(MODES), 'quietHours': {'enabled': False, 'start': '22:00', 'end': '07:00', 'brightness': 10}, 'idleReturnSeconds': 300, 'profile': 'balanced', 'hiddenHomeSuspend': False, 'eventOverlays': False}
COMMANDS = {'show_mode', 'photo_next', 'photo_previous', 'photo_pause', 'photo_resume', 'photo_hold', 'set_profile'}
TERMINAL = {'applied', 'dispatched', 'rejected', 'failed', 'expired'}
IDENTIFIER = re.compile(r'^[a-zA-Z0-9_-]{1,48}$')
ASSET_ID = re.compile(r'^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$')
CLOCK = re.compile(r'^(?:[01][0-9]|2[0-3]):[0-5][0-9]$')
MAX_BODY = 32 * 1024

class FrameError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status

def integer(value, minimum, maximum):
    return isinstance(value, int) and not isinstance(value, bool) and minimum <= value <= maximum

def canonical_origin(value):
    """Return the browser Origin form for a credential-free HTTPS origin."""
    if not isinstance(value, str) or any(ord(char) <= 0x20 or ord(char) == 0x7f for char in value): return None
    try:
        origin = urlsplit(value)
        if origin.scheme.lower() != 'https' or not origin.hostname or origin.username is not None or origin.password is not None or origin.path not in ('','/') or origin.query or origin.fragment: return None
        host = origin.hostname.lower()
        port = origin.port
    except ValueError:
        return None
    authority = f'[{host}]' if ':' in host else host
    return f'https://{authority}' if port in (None, 443) else f'https://{authority}:{port}'

def settings_patch(existing, patch, profiles):
    if not isinstance(patch, dict) or set(patch) - set(DEFAULTS):
        raise FrameError('Unsupported settings')
    result = json.loads(json.dumps(existing))
    for key, value in patch.items():
        valid = False
        if key == 'modeOrder':
            valid = isinstance(value, list) and 1 <= len(value) <= len(MODES) and all(isinstance(x, str) and x in MODES for x in value) and len(set(value)) == len(value) and value[0] == 'photos'
        elif key == 'quietHours':
            valid = isinstance(value, dict) and set(value) == {'enabled', 'start', 'end', 'brightness'} and isinstance(value['enabled'], bool) and all(isinstance(value[x], str) and CLOCK.fullmatch(value[x]) for x in ('start','end')) and integer(value['brightness'], 1, 30) and value['start'] != value['end']
        elif key == 'idleReturnSeconds':
            valid = integer(value, 30, 3600)
        elif key == 'profile':
            valid = isinstance(value, str) and value in profiles
        else:
            valid = isinstance(value, bool)
        if not valid:
            raise FrameError('Invalid ' + key)
        result[key] = value
    return result

class FrameStore:
    def __init__(self, path, devices, profiles, clock=None, preferences_export=None):
        self.path = str(path)
        self.profiles = list(profiles)
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.preferences_export = Path(preferences_export) if preferences_export else None
        self.export_lock = threading.Lock()
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.db() as db:
            db.executescript('''
            CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, label TEXT NOT NULL, settings TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL DEFAULT '{}', seen INTEGER);
            CREATE TABLE IF NOT EXISTS commands (id TEXT PRIMARY KEY, device TEXT NOT NULL, payload TEXT NOT NULL, issued INTEGER NOT NULL, expires INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'queued', message TEXT NOT NULL DEFAULT '', acknowledged INTEGER);
            CREATE INDEX IF NOT EXISTS device_commands ON commands(device, issued);
            CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, device TEXT NOT NULL, payload TEXT NOT NULL, issued INTEGER NOT NULL, expires INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS device_events ON events(device, issued);
            CREATE TABLE IF NOT EXISTS preferences (device TEXT NOT NULL, asset TEXT NOT NULL, preference TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(device, asset));
            ''')
            for device, conf in devices.items():
                if not IDENTIFIER.fullmatch(device): raise FrameError('Invalid device identifier')
                defaults = json.loads(json.dumps(DEFAULTS))
                if defaults['profile'] not in profiles: defaults['profile'] = profiles[0]
                initial = settings_patch(defaults, conf.get('settings', {}), profiles)
                db.execute('INSERT OR IGNORE INTO devices(id,label,settings) VALUES(?,?,?)', (device, str(conf.get('label', device))[:80], json.dumps(initial)))
        self.export_preferences()

    @contextmanager
    def db(self):
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute('PRAGMA journal_mode=WAL')
        connection.execute('PRAGMA busy_timeout=10000')
        try:
            connection.execute('BEGIN IMMEDIATE')
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _device(self, db, device):
        row = db.execute('SELECT * FROM devices WHERE id=?', (device,)).fetchone()
        if row is None: raise FrameError('Unknown frame', 404)
        return row

    def _expire(self, db):
        db.execute("UPDATE commands SET status='expired' WHERE status='queued' AND expires <= ?", (self.clock(),))
        db.execute("DELETE FROM commands WHERE status != 'queued' AND issued < ?", (self.clock() - 7*86400000,))
        # Keep the history bounded even on a busy remote.
        db.execute('DELETE FROM commands WHERE id IN (SELECT id FROM commands ORDER BY issued DESC LIMIT -1 OFFSET 1000)')

    def _status(self, raw):
        if not isinstance(raw, dict): raise FrameError('Invalid status')
        result = {}
        for key in ('mode', 'appVersion', 'lastError', 'currentAssetId', 'kioskDeviceId', 'profile', 'bootId'):
            value = raw.get(key)
            if isinstance(value, str):
                if key == 'mode' and value not in MODES: continue
                if key == 'currentAssetId' and not ASSET_ID.fullmatch(value): continue
                result[key] = re.sub(r'[^a-zA-Z0-9 _:.\-]', '', value)[:80]
        for key in ('photosPaused', 'offline'):
            if isinstance(raw.get(key), bool): result[key] = raw[key]
        for key in ('lastPaintAt', 'lastWeatherAt', 'lastPhotoAt'):
            value = raw.get(key)
            result[key] = value if integer(value, 0, self.clock()+60000) else None
        for key in ('recoveryCount', 'offlineAssets', 'offlineBytes'):
            value = raw.get(key)
            if integer(value, 0, 1_000_000_000): result[key] = value
        return result

    def poll(self, device, body):
        if not isinstance(body, dict): raise FrameError('Expected object')
        acks = body.get('acks', [])
        if not isinstance(acks, list) or len(acks) > 16: raise FrameError('Invalid acknowledgements')
        status = self._status(body.get('status', {}))
        with self.db() as db:
            row = self._device(db, device)
            self._expire(db)
            for ack in acks:
                if not isinstance(ack, dict) or not isinstance(ack.get('id'), str) or ack.get('status') not in TERMINAL: raise FrameError('Invalid acknowledgement')
                message = re.sub(r'[^a-zA-Z0-9 _:.\-]', '', str(ack.get('message', '')))[:80]
                db.execute("UPDATE commands SET status=?,message=?,acknowledged=? WHERE id=? AND device=? AND status='queued'", (ack['status'],message,self.clock(),ack['id'],device))
            db.execute('UPDATE devices SET status=?,seen=? WHERE id=?', (json.dumps(status),self.clock(),device))
            commands = [json.loads(c['payload']) for c in db.execute("SELECT payload FROM commands WHERE device=? AND status='queued' AND expires>? ORDER BY issued,id LIMIT 1", (device,self.clock()))]
            return {'schema':1,'deviceId':device,'serverTime':self.clock(),'pollAfterMs':5000,'settingsRevision':row['revision'],'settings':json.loads(row['settings']),'commands':commands,'hiddenAssets':[p['asset'] for p in db.execute("SELECT asset FROM preferences WHERE device=? AND preference='hide' ORDER BY asset LIMIT 1000",(device,))],'events':[json.loads(e['payload']) for e in db.execute('SELECT payload FROM events WHERE device=? AND expires>? ORDER BY issued DESC LIMIT 1',(device,self.clock()))] if json.loads(row['settings']).get('eventOverlays') else []}

    def command(self, device, payload):
        if not isinstance(payload,dict) or not isinstance(payload.get('type'),str) or payload.get('type') not in COMMANDS: raise FrameError('Unsupported command')
        kind = payload['type']
        fields = {'type'} | ({'mode'} if kind=='show_mode' else {'profile'} if kind=='set_profile' else {'durationSeconds'} if kind=='photo_hold' else set())
        if set(payload) != fields: raise FrameError('Unexpected command fields')
        if kind=='show_mode' and payload['mode'] not in MODES: raise FrameError('Unknown view')
        if kind=='set_profile' and payload['profile'] not in self.profiles: raise FrameError('Unknown profile')
        if kind=='photo_hold' and not integer(payload['durationSeconds'],15,3600): raise FrameError('Invalid hold duration')
        with self.db() as db:
            row = self._device(db, device)
            self._expire(db)
            if row['seen'] is None or self.clock()-row['seen']>90000: raise FrameError('Frame is offline; commands are not queued for later',409)
            count = db.execute("SELECT COUNT(*) FROM commands WHERE device=? AND status='queued'", (device,)).fetchone()[0]
            if count>=16: raise FrameError('Frame command queue is full',429)
            if kind=='set_profile':
                updated=settings_patch(json.loads(row['settings']),{'profile':payload['profile']},self.profiles)
                db.execute('UPDATE devices SET settings=?,revision=revision+1 WHERE id=?',(json.dumps(updated),device))
            command = dict(payload, id=str(uuid.uuid4()),issuedAt=self.clock(),expiresAt=self.clock()+60000)
            db.execute('INSERT INTO commands(id,device,payload,issued,expires) VALUES(?,?,?,?,?)', (command['id'],device,json.dumps(command),command['issuedAt'],command['expiresAt']))
            return command

    def settings(self, device, patch):
        with self.db() as db:
            row=self._device(db,device)
            updated=settings_patch(json.loads(row['settings']),patch,self.profiles)
            db.execute('UPDATE devices SET settings=?,revision=revision+1 WHERE id=?',(json.dumps(updated),device))
            return {'settings':updated,'settingsRevision':row['revision']+1}

    def event(self, device, payload):
        if not isinstance(payload,dict) or set(payload)!={'type','text','expiresInSeconds'}: raise FrameError('Invalid event')
        kind=payload['type']; message=payload['text']; seconds=payload['expiresInSeconds']
        if kind not in ('calendar','reviewed_bird') or not isinstance(message,str) or not 1<=len(message.strip())<=100 or any(ord(c)<32 or c in '<>' for c in message) or not integer(seconds,30,300): raise FrameError('Invalid event')
        with self.db() as db:
            row=self._device(db,device)
            if not json.loads(row['settings']).get('eventOverlays'): raise FrameError('Event overlays are disabled for this frame',409)
            recent=db.execute('SELECT issued FROM events WHERE device=? ORDER BY issued DESC LIMIT 1',(device,)).fetchone()
            if recent and self.clock()-recent['issued']<900000: raise FrameError('Frame overlays are limited to one every 15 minutes',429)
            event={'id':str(uuid.uuid4()),'type':kind,'text':message.strip(),'issuedAt':self.clock(),'expiresAt':self.clock()+seconds*1000}
            db.execute('INSERT INTO events VALUES(?,?,?,?,?)',(event['id'],device,json.dumps(event),event['issuedAt'],event['expiresAt']))
            db.execute('DELETE FROM events WHERE expires < ?',(self.clock()-86400000,))
            return event

    def preferences(self, device):
        with self.db() as db:
            self._device(db,device)
            return [dict(row) for row in db.execute('SELECT asset AS assetId,preference,updated FROM preferences WHERE device=? ORDER BY updated DESC',(device,))]

    def feedback(self, device, payload):
        if not isinstance(payload,dict) or set(payload)!={'assetId','preference'} or not isinstance(payload['assetId'],str) or not ASSET_ID.fullmatch(payload['assetId']) or payload['preference'] not in ('more','less','hide','clear'): raise FrameError('Invalid photo preference')
        with self.db() as db:
            self._device(db,device)
            if payload['preference']=='clear': db.execute('DELETE FROM preferences WHERE device=? AND asset=?',(device,payload['assetId']))
            else:
                if db.execute('SELECT COUNT(*) FROM preferences WHERE device=?',(device,)).fetchone()[0]>=1000 and db.execute('SELECT 1 FROM preferences WHERE device=? AND asset=?',(device,payload['assetId'])).fetchone() is None: raise FrameError('Preference limit reached; clear old choices first',409)
                db.execute('INSERT INTO preferences VALUES(?,?,?,?) ON CONFLICT(device,asset) DO UPDATE SET preference=excluded.preference,updated=excluded.updated',(device,payload['assetId'],payload['preference'],self.clock()))
        self.export_preferences()
        return {'saved':True,'assetId':payload['assetId'],'preference':payload['preference']}

    def export_preferences(self):
        if not self.preferences_export: return
        with self.export_lock:
            with self.db() as db:
                devices={row['id']:{'assets':{}} for row in db.execute('SELECT id FROM devices')}
                for row in db.execute('SELECT * FROM preferences'): devices[row['device']]['assets'][row['asset']]=row['preference']
            self.preferences_export.parent.mkdir(parents=True,exist_ok=True)
            temp=self.preferences_export.with_suffix('.tmp')
            temp.write_text(json.dumps({'version':1,'updatedAt':self.clock(),'devices':devices}),encoding='utf-8')
            os.chmod(temp,0o640)
            os.replace(temp,self.preferences_export)

    def state(self):
        with self.db() as db:
            self._expire(db)
            frames=[]
            for row in db.execute('SELECT * FROM devices ORDER BY label'):
                commands=[dict(id=c['id'],type=json.loads(c['payload'])['type'],issuedAt=c['issued'],expiresAt=c['expires'],status=c['status'],message=c['message']) for c in db.execute('SELECT * FROM commands WHERE device=? ORDER BY issued DESC,rowid DESC LIMIT 20',(row['id'],))]
                frames.append({'id':row['id'],'label':row['label'],'settings':json.loads(row['settings']),'settingsRevision':row['revision'],'status':json.loads(row['status']),'lastSeenAt':row['seen'],'online':row['seen'] is not None and self.clock()-row['seen']<=90000,'commands':commands})
            return {'schema':1,'serverTime':self.clock(),'profiles':self.profiles,'devices':frames}

class FrameAuth:
    def __init__(self, config):
        self.username=config['operatorUsername']
        self.password=config['operatorPassword']
        self.operator_token=config['operatorToken']
        self.devices=config['devices']
        if not self.username or len(self.password)<20 or len(self.operator_token)<32 or any(len(d.get('token',''))<32 for d in self.devices.values()): raise FrameError('Configure unique strong credentials before startup')
        tokens=[self.operator_token]+[d['token'] for d in self.devices.values()]
        if len(tokens)!=len(set(tokens)): raise FrameError('Tokens must have distinct roles and device scopes')
    def role(self, authorization):
        if not isinstance(authorization,str) or len(authorization)>1024:return None
        if authorization.startswith('Bearer '):
            token=authorization[7:]
            if hmac.compare_digest(token.encode(),self.operator_token.encode()):return ('operator',None)
            for device,config in self.devices.items():
                if hmac.compare_digest(token.encode(),config['token'].encode()):return ('device',device)
        if authorization.startswith('Basic '):
            try:
                decoded=base64.b64decode(authorization[6:],validate=True).decode()
                username,password=decoded.split(':',1)
                if hmac.compare_digest(username.encode(),self.username.encode()) and hmac.compare_digest(password.encode(),self.password.encode()):return ('operator',None)
            except (ValueError,UnicodeError):pass
        return None

class BoundedServer(ThreadingHTTPServer):
    daemon_threads=True
    request_queue_size=16
    def __init__(self,*args,**kwargs):
        self.admission=threading.BoundedSemaphore(16)
        super().__init__(*args,**kwargs)
    def process_request(self,request,address):
        if not self.admission.acquire(blocking=False):request.close();return
        try:super().process_request(request,address)
        except Exception:self.admission.release();raise
    def process_request_thread(self,request,address):
        try:super().process_request_thread(request,address)
        finally:self.admission.release()

def make_server(config,store,host='127.0.0.1',port=8092):
    auth=FrameAuth(config)
    csrf=secrets.token_urlsafe(32)
    origin=canonical_origin(config.get('publicOrigin',''))
    if origin is None: raise FrameError('publicOrigin must be a credential-free HTTPS origin')
    ui=Path(__file__).with_name('index.html')
    class Handler(BaseHTTPRequestHandler):
        server_version='FrameCompanion/1'
        def setup(self):
            super().setup();self.connection.settimeout(8)
        def log_message(self,*_args):pass # Never log Authorization, query strings, photos, or household state.
        def reply(self,status,data,content_type='application/json',challenge=False):
            body=json.dumps(data,separators=(',',':')).encode() if content_type=='application/json' else data
            self.send_response(status)
            self.send_header('Content-Type',content_type)
            self.send_header('Content-Length',str(len(body)))
            self.send_header('Cache-Control','no-store')
            self.send_header('X-Content-Type-Options','nosniff')
            self.send_header('Referrer-Policy','no-referrer')
            self.send_header('Content-Security-Policy',"default-src 'none'; connect-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
            self.send_header('X-Frame-Options','DENY')
            if challenge:self.send_header('WWW-Authenticate','Basic realm="Frame remote", charset="UTF-8"')
            self.end_headers()
            try:self.wfile.write(body)
            except (BrokenPipeError,ConnectionResetError):pass
        def handle_api(self):
            try:
                path=urlsplit(self.path).path
                if path=='/healthz' and self.command=='GET':return self.reply(200,{'status':'healthy'})
                role=auth.role(self.headers.get('Authorization'))
                if role is None:return self.reply(401,{'error':'Authentication required'},challenge=True)
                if self.command not in ('GET','POST'):raise FrameError('Method not allowed',405)
                device_route=path=='/device/poll'
                if device_route and role[0]!='device' or not device_route and role[0]!='operator':raise FrameError('Credential is not authorized for this route',403)
                if self.command=='POST':
                    if path not in ('/device/poll','/api/command','/api/settings','/api/feedback','/api/event'):raise FrameError('Not found',404)
                    raw_origin=self.headers.get('Origin')
                    request_origin=canonical_origin(raw_origin) if raw_origin is not None else None
                    if raw_origin is not None and (request_origin is None or request_origin!=origin):raise FrameError('Origin rejected',403)
                    if role[0]=='operator' and self.headers.get('Authorization','').startswith('Basic '):
                        if request_origin!=origin or not hmac.compare_digest(self.headers.get('X-Frame-CSRF',''),csrf):raise FrameError('CSRF check failed',403)
                    if self.headers.get('Transfer-Encoding'):raise FrameError('Chunked requests are not supported')
                    try:size=int(self.headers.get('Content-Length','0'))
                    except ValueError:raise FrameError('Invalid content length')
                    if size<2 or size>MAX_BODY:raise FrameError('Request size rejected',413)
                    if self.headers.get_content_type()!='application/json':raise FrameError('JSON required',415)
                    try:body=json.loads(self.rfile.read(size))
                    except (ValueError,UnicodeError):raise FrameError('Invalid JSON')
                    if not isinstance(body,dict):raise FrameError('Expected object')
                    if device_route:return self.reply(200,store.poll(role[1],body))
                    device=body.get('deviceId')
                    if not isinstance(device,str) or not IDENTIFIER.fullmatch(device):raise FrameError('Invalid device')
                    if path=='/api/command':return self.reply(200,store.command(device,body.get('command')))
                    if path=='/api/settings':return self.reply(200,store.settings(device,body.get('patch')))
                    if path=='/api/feedback':return self.reply(200,store.feedback(device,body.get('feedback')))
                    if path=='/api/event':return self.reply(200,store.event(device,body.get('event')))
                elif path=='/api/state':return self.reply(200,dict(store.state(),csrfToken=csrf))
                elif path=='/api/preferences':
                    from urllib.parse import parse_qs
                    values=parse_qs(urlsplit(self.path).query)
                    return self.reply(200,{'preferences':store.preferences(values.get('deviceId',[''])[0])})
                elif path=='/':return self.reply(200,ui.read_bytes(),'text/html; charset=utf-8')
                raise FrameError('Not found',404)
            except FrameError as error:self.reply(error.status,{'error':str(error)})
            except (sqlite3.Error,OSError,ValueError,TypeError,KeyError):self.reply(503,{'error':'Frame service temporarily unavailable'})
        do_GET=handle_api
        do_POST=handle_api
        do_DELETE=handle_api
        do_PUT=handle_api
        do_PATCH=handle_api
        do_OPTIONS=handle_api
    return BoundedServer((host,port),Handler)

def main():
    config_path=Path(os.environ.get('FRAME_CONFIG_FILE','/run/secrets/frame_config'))
    config=json.loads(config_path.read_text())
    if canonical_origin(config.get('publicOrigin','')) is None:raise SystemExit('publicOrigin must be a credential-free HTTPS origin')
    profiles=config.get('profiles',['balanced','family','photography'])
    if not profiles or not all(isinstance(p,str) and IDENTIFIER.fullmatch(p) for p in profiles):raise SystemExit('Invalid profiles')
    state_dir=Path(os.environ.get('FRAME_STATE_DIR','/data'))
    store=FrameStore(state_dir/'state.db',config['devices'],profiles,preferences_export=state_dir/'export'/'preferences.json')
    server=make_server(config,store,os.environ.get('FRAME_BIND','0.0.0.0'),int(os.environ.get('FRAME_PORT','8092')))
    print('Frame companion ready',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()

if __name__=='__main__':main()
