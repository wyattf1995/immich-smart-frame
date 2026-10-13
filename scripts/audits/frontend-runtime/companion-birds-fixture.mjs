/**
 * Local-only synthetic fixture for companion and BirdNET browser profiling.
 * It deliberately contains no production credentials, network proxying, or media.
 */
import { readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL9xQAAAABJRU5ErkJggg==', 'base64');
const DEFAULTS = Object.freeze({ preferences: 200, audioHz: 2, recentCount: 10, summaryCount: 200 });
let config = { ...DEFAULTS };
let fixtureState;
const sseTimers = new Set();

export const fixtureMetadata = Object.freeze({
  localhostOnly: true,
  defaults: DEFAULTS,
  pages: ['/companion', '/birds'],
  companionEndpoints: ['/api/state', '/api/preferences?deviceId=fixture-main', '/api/command', '/api/settings', '/api/feedback', '/fixture/config'],
  birdEndpoints: [
    '/api/v2/health', '/api/v2/streams/sources', '/api/v2/detections/recent?limit=10',
    '/api/v2/analytics/species/daily', '/api/v2/dashboard/kpis',
    '/api/v2/analytics/time/distribution/hourly', '/api/v2/insights/expected-today',
    '/api/v2/weather/latest', '/api/v2/analytics/species/summary',
    '/api/v2/detections/stream', '/api/v2/streams/audio-level', '/api/v2/media/species-image',
  ],
  selectors: {
    companion: ['#frameSelect', '#preferences', '#settings', '[data-command]', '#applyProfile'],
    birds: ['#speciesGrid', '#recentList', '#audioMeterLevel', '[data-species-period]', '[data-review-filter]'],
  },
});
export const metadata = fixtureMetadata;

function localRequest(req) {
  const address = req.socket?.remoteAddress;
  return typeof address === 'string' && LOOPBACK.has(address);
}
function send(res, status, body, type = 'application/json; charset=utf-8') {
  const bytes = Buffer.isBuffer(body) ? body : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body));
  res.writeHead(status, { 'content-type': type, 'content-length': String(bytes.length), 'cache-control': 'no-store' });
  res.end(bytes);
}
function json(res, value, status = 200) { send(res, status, value); }
function clampInt(value, fallback, min, max) {
  return Number.isInteger(value) && value >= min && value <= max ? value : fallback;
}
function nowIso(offsetSeconds = 0) { return new Date(Date.now() - offsetSeconds * 1000).toISOString(); }
function species(count) {
  return Array.from({ length: count }, (_, index) => ({
    common_name: `Synthetic Finch ${index + 1}`,
    scientific_name: `Fringilla synthetica ${index + 1}`,
    count: (index % 17) + 1,
    last_heard: nowIso(index * 37),
    is_new_this_year: index % 31 === 0,
    days_since_first_seen: index % 9,
    days_since_last_seen: index % 21,
  }));
}
function detections(count) {
  return Array.from({ length: count }, (_, index) => ({
    id: index + 1,
    timestamp: nowIso(index * 73),
    time: new Date(Date.now() - index * 73_000).toTimeString().slice(0, 5),
    commonName: `Synthetic Finch ${(index % Math.max(1, config.summaryCount)) + 1}`,
    scientificName: `Fringilla synthetica ${(index % Math.max(1, config.summaryCount)) + 1}`,
    confidence: 0.54 + (index % 40) / 100,
    verified: index % 7 === 0 ? 'correct' : index % 11 === 0 ? 'false_positive' : '',
    source: { id: 'synthetic-mic', displayName: 'Synthetic microphone', state: 'running' },
  }));
}
function companionDevices() {
  return Array.from({ length: 5 }, (_, index) => ({
    id: index === 0 ? 'fixture-main' : `fixture-${index + 1}`,
    label: index === 0 ? 'Synthetic living room' : `Synthetic frame ${index + 1}`,
    online: true,
    lastSeenAt: Date.now() - index * 1_000,
    settingsRevision: 1,
    settings: { modeOrder: ['photos', 'home', 'weather', 'birds'], profile: 'balanced', eventOverlays: true, quietHours: { enabled: false, start: '22:00', end: '07:00', brightness: 10 }, idleReturnSeconds: 300 },
    status: { mode: 'photos', photosPaused: false, offline: false, currentAssetId: '11111111-1111-4111-8111-111111111111', lastPhotoAt: Date.now() - 2_000, lastWeatherAt: Date.now() - 5_000, offlineAssets: 24, appVersion: 'fixture', recoveryCount: 0 },
    commands: Array.from({ length: 10 }, (_, command) => ({ id: `fixture-command-${index}-${command}`, type: 'photo_next', issuedAt: Date.now() - command * 1_000, expiresAt: Date.now() + 60_000, status: 'applied', message: '' })),
  }));
}
function preferenceRows(count) {
  return Array.from({ length: count }, (_, index) => ({ assetId: `${String(index + 1).padStart(8, '0')}-1111-4111-8111-111111111111`, preference: index % 3 === 0 ? 'hide' : index % 3 === 1 ? 'less' : 'more', updated: Date.now() - index * 1_000 }));
}
function resetFixtureState() {
  fixtureState = { devices: companionDevices(), preferences: preferenceRows(config.preferences), requests: {}, commandId: 0 };
}
function ensureFixtureState() { if (!fixtureState) resetFixtureState(); return fixtureState; }
function countRequest(method, path) {
  const state=ensureFixtureState(); const key=`${method} ${path}`;
  state.requests[key]=(state.requests[key] || 0)+1;
}
async function readJsonBody(req) {
  let raw='';
  for await (const chunk of req) { raw += chunk; if (raw.length > 32_768) throw new Error('fixture request too large'); }
  try { const body=JSON.parse(raw); return body && typeof body === 'object' && !Array.isArray(body) ? body : null; } catch { return null; }
}
function selectedDevice(body) { return ensureFixtureState().devices.find(device => device.id === body?.deviceId) || null; }
function audioPayload(tick) { return { levels: { 'synthetic-mic': { level: 35 + (tick % 50) } } }; }
function startSse(req, res, audio) {
  res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-store, no-transform', connection: 'keep-alive', 'x-accel-buffering': 'no' });
  res.write(': synthetic fixture\n\n');
  if (!audio || config.audioHz === 0) return;
  let tick = 0;
  const emit = () => res.write(`event: audio-level\ndata: ${JSON.stringify(audioPayload(tick++))}\n\n`);
  emit();
  const timer = setInterval(emit, Math.round(1_000 / config.audioHz));
  sseTimers.add(timer);
  const stop = () => { clearInterval(timer); sseTimers.delete(timer); };
  req.once('close', stop); res.once('close', stop);
}
async function readPage(repoRoot, relative) {
  const root = resolve(repoRoot);
  return readFile(join(root, relative), 'utf8');
}
async function configure(req, res) {
  let raw = '';
  for await (const chunk of req) { raw += chunk; if (raw.length > 4_096) return json(res, { error: 'fixture config too large' }, 413); }
  let patch;
  try { patch = JSON.parse(raw); } catch { return json(res, { error: 'invalid fixture JSON' }, 400); }
  if (!patch || typeof patch !== 'object' || Array.isArray(patch)) return json(res, { error: 'fixture config must be an object' }, 400);
  const allowed = new Set(['preferences', 'audioHz', 'recentCount', 'summaryCount']);
  if (Object.keys(patch).some(key => !allowed.has(key))) return json(res, { error: 'unknown fixture key' }, 400);
  if ('audioHz' in patch && ![0, 2, 20].includes(patch.audioHz)) return json(res, { error: 'audioHz must be 0, 2, or 20' }, 400);
  config = {
    preferences: clampInt(patch.preferences, config.preferences, 0, 1_000),
    audioHz: 'audioHz' in patch ? patch.audioHz : config.audioHz,
    recentCount: clampInt(patch.recentCount, config.recentCount, 0, 500),
    summaryCount: clampInt(patch.summaryCount, config.summaryCount, 0, 500),
  };
  resetFixtureState();
  return json(res, { config, requests: fixtureState.requests, metadata: fixtureMetadata });
}

/** Return true only when this local fixture handled the request. */
export async function handleCompanionBirds(req, res, url, repoRoot) {
  const path = url.pathname;
  const known = path === '/companion' || path === '/companion/' || path === '/birds' || path === '/birds/' || path === '/fixture/config' || path.startsWith('/api/');
  if (!known) return false;
  if (!localRequest(req)) { json(res, { error: 'fixture accepts localhost only' }, 403); return true; }
  countRequest(req.method || 'GET', path);
  if (path === '/fixture/config') {
    if (req.method === 'GET') { json(res, { config, requests: ensureFixtureState().requests, metadata: fixtureMetadata }); return true; }
    if (req.method !== 'POST') { json(res, { error: 'POST required' }, 405); return true; }
    await configure(req, res); return true;
  }
  if ((path === '/companion' || path === '/companion/') && req.method === 'GET') { send(res, 200, await readPage(repoRoot, 'companion/index.html'), 'text/html; charset=utf-8'); return true; }
  if ((path === '/birds' || path === '/birds/') && req.method === 'GET') { send(res, 200, await readPage(repoRoot, 'birdnet/frame-view/index.html'), 'text/html; charset=utf-8'); return true; }
  if (path === '/api/state' && req.method === 'GET') { json(res, { schema: 1, serverTime: Date.now(), csrfToken: 'synthetic-csrf-token', profiles: ['balanced', 'family', 'photography'], devices: ensureFixtureState().devices }); return true; }
  if (path === '/api/preferences' && req.method === 'GET') { json(res, { preferences: ensureFixtureState().preferences }); return true; }
  if (['/api/command', '/api/settings', '/api/feedback'].includes(path) && req.method === 'POST') {
    let body; try { body=await readJsonBody(req); } catch (error) { json(res,{error:error.message},413); return true; }
    const device=selectedDevice(body);
    if (!device) { json(res,{error:'unknown synthetic device'},404); return true; }
    if (path === '/api/settings') {
      const patch=body.patch;
      if (!patch || typeof patch !== 'object' || Array.isArray(patch)) { json(res,{error:'settings patch required'},400); return true; }
      device.settings={...device.settings,...patch,quietHours:patch.quietHours ? {...device.settings.quietHours,...patch.quietHours} : device.settings.quietHours};
      device.settingsRevision += 1;
      json(res,{settings:device.settings,settingsRevision:device.settingsRevision}); return true;
    }
    if (path === '/api/feedback') {
      const feedback=body.feedback;
      if (!feedback || typeof feedback.assetId !== 'string' || !['more','less','hide','clear'].includes(feedback.preference)) { json(res,{error:'valid feedback required'},400); return true; }
      const state=ensureFixtureState(); const at=state.preferences.findIndex(item => item.assetId === feedback.assetId);
      if (feedback.preference === 'clear') { if (at >= 0) state.preferences.splice(at,1); }
      else if (at >= 0) state.preferences[at]={...state.preferences[at],preference:feedback.preference,updated:Date.now()};
      else state.preferences.unshift({assetId:feedback.assetId,preference:feedback.preference,updated:Date.now()});
      json(res,{saved:true,assetId:feedback.assetId,preference:feedback.preference}); return true;
    }
    const command=body.command;
    if (!command || typeof command.type !== 'string') { json(res,{error:'command required'},400); return true; }
    if (command.type === 'show_mode' && typeof command.mode === 'string') device.status.mode=command.mode;
    if (command.type === 'photo_pause') device.status.photosPaused=true;
    if (command.type === 'photo_resume') device.status.photosPaused=false;
    if (command.type === 'set_profile' && typeof command.profile === 'string') { device.settings.profile=command.profile; device.settingsRevision += 1; }
    const state=ensureFixtureState(); const record={id:`fixture-command-live-${++state.commandId}`,type:command.type,issuedAt:Date.now(),expiresAt:Date.now()+60_000,status:'applied',message:''};
    device.commands.unshift(record); device.commands=device.commands.slice(0,20);
    json(res,record); return true;
  }
  if (path === '/api/v2/health' && req.method === 'GET') { json(res, { status: 'healthy', version: 'synthetic' }); return true; }
  if (path === '/api/v2/streams/sources' && req.method === 'GET') { json(res, { sources: [{ id: 'synthetic-mic', displayName: 'Synthetic microphone', state: 'running' }] }); return true; }
  if (path === '/api/v2/detections/recent' && req.method === 'GET') { json(res, detections(config.recentCount)); return true; }
  if ((path === '/api/v2/analytics/species/daily' || path === '/api/v2/analytics/species/summary') && req.method === 'GET') { json(res, species(config.summaryCount)); return true; }
  if (path === '/api/v2/dashboard/kpis' && req.method === 'GET') { json(res, { today_detections: config.summaryCount * 3, lifetime_species: config.summaryCount + 42, detection_streak: { days: 12 } }); return true; }
  if (path === '/api/v2/analytics/time/distribution/hourly' && req.method === 'GET') { json(res, { hours: Array.from({ length: 24 }, (_, hour) => ({ hour, count: (hour * 7) % 19 })) }); return true; }
  if (path === '/api/v2/insights/expected-today' && req.method === 'GET') { json(res, { species: [{ common_name: 'Synthetic Finch 1', years_seen: 3, last_seen_date: '2026-10-11' }, { common_name: 'Synthetic Finch 2', years_seen: 2, last_seen_date: '2026-10-10' }] }); return true; }
  if (path === '/api/v2/weather/latest' && req.method === 'GET') { json(res, { daily: { sunrise: '06:45', sunset: '18:30' }, current: { temperature: 18, condition: 'clear' } }); return true; }
  if (path === '/api/v2/detections/stream' && req.method === 'GET') { startSse(req, res, false); return true; }
  if (path === '/api/v2/streams/audio-level' && req.method === 'GET') { startSse(req, res, true); return true; }
  if ((path === '/api/v2/media/species-image' || path.startsWith('/api/v2/spectrogram/') || path.startsWith('/api/v2/audio/')) && req.method === 'GET') { send(res, 200, PNG, 'image/png'); return true; }
  if (path === '/api/v2/media/species-image/info' && req.method === 'GET') { json(res, { authorName: 'Synthetic fixture', licenseName: 'CC0', sourceProvider: 'local-only' }); return true; }
  json(res, { error: 'fixture endpoint not found' }, 404); return true;
}

export function close() {
  for (const timer of sseTimers) clearInterval(timer);
  sseTimers.clear();
}
