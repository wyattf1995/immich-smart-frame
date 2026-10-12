import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../../examples/frameos/frameos-touchscreen-panel.html', import.meta.url), 'utf8');
const script = html.match(/<script>\s*([\s\S]*?)\s*<\/script>/)[1];

function harness() {
  let now = 0;
  let wallClock = 1_000_000;
  let nextTimer = 1;
  let mounts = 0;
  let stateEnumerations = 0;
  const timers = new Map();
  const listeners = new Map();
  const childListeners = new Map();
  const classes = new Set();
  const live = { states: {}, connected: false, connection: { connected: false }, callService() { throw new Error('commands are outside discovery'); } };
  const app = { hass: live };
  const root = { nodeType: 9, querySelector(selector) { return selector === 'home-assistant' ? app : null; }, querySelectorAll() { return []; } };
  const panel = {
    src: '', contentDocument: root,
    contentWindow: { location: { pathname: '/frameos-touchscreen/home', search: '?kiosk', origin: 'https://frame.example' }, focus() {}, addEventListener(name, callback) { childListeners.set(name, callback); }, removeEventListener(name, callback) { if (childListeners.get(name) === callback) childListeners.delete(name); } },
    classList: { toggle(name, active) { if (active) classes.add(name); else classes.delete(name); } },
    addEventListener(name, callback) { listeners.set(`panel:${name}`, callback); }, focus() {},
  };
  const loading = { hidden: false, textContent: '', classList: { add() {}, remove() {} } };
  const context = {
    document: { visibilityState: 'visible', getElementById(id) { return ({ panel, 'custom-home': { hidden: true, focus() {} }, 'home-chrome': { hidden: true }, loading, status: { textContent: '' } })[id]; } },
    location: { origin: 'https://frame.example', pathname: '/local/frameos-touchscreen-panel.html', search: '', hash: '#home' },
    history: { replaceState(_state, _title, url) { context.location.hash = url.slice(url.indexOf('#')); } },
    Date: { now: () => wallClock },
    performance: { now: () => now },
    MutationObserver: class { observe() {} disconnect() {} },
    setTimeout(callback, delay) { const id = nextTimer++; timers.set(id, { id, callback, due: now + delay, delay, active: true }); return id; },
    clearTimeout(id) { const timer = timers.get(id); if (timer) timer.active = false; },
    FrameOSCustomHome: { mount() { mounts += 1; return { destroy() {}, refresh() {} }; } },
    addEventListener(name, callback) { listeners.set(name, callback); },
  };
  context.window = context;
  vm.runInNewContext(script, context, { filename: 'frameos-touchscreen-panel.inline.js' });
  const nextDue = (limit) => [...timers.values()].filter((timer) => timer.active && timer.due <= limit).sort((a, b) => a.due - b.due || a.id - b.id)[0];
  const advanceTo = (limit) => { for (let timer = nextDue(limit); timer; timer = nextDue(limit)) { timer.active = false; now = timer.due; timer.callback(); } now = limit; };
  const activeTimers = (delay) => [...timers.values()].filter((timer) => timer.active && (delay === undefined || timer.delay === delay));
  const start = () => listeners.get('panel:load')();
  const makeConnected = () => { live.connected = true; live.connection.connected = true; };
  const makeDisconnectedNoEnumeration = () => {
    live.connected = false; live.connection.connected = false;
    live.states = new Proxy({}, { ownKeys() { stateEnumerations += 1; throw new Error('disconnected state enumeration'); } });
  };
  return { context, panel, root, live, loading, listeners, childListeners, start, advanceTo, activeTimers, makeConnected, makeDisconnectedNoEnumeration, mounts: () => mounts, stateEnumerations: () => stateEnumerations, now: () => now, setWallClock: (value) => { wallClock = value; } };
}

function reachStillConnecting(h) {
  h.start();
  h.advanceTo(12_000);
  assert.match(h.loading.textContent, /Home still connecting/, 'the existing 12-second still-connecting UX remains visible');
  assert.equal(h.activeTimers(1_000).length, 1, 'only one 1 Hz late discovery timer is armed after the fast window');
}

{
  const h = harness();
  reachStillConnecting(h);
  h.makeConnected();
  h.advanceTo(13_000);
  assert.equal(h.mounts(), 1, 'healthy Home Assistant that appears after the initial window mounts once');
}

{
  const h = harness();
  h.makeDisconnectedNoEnumeration();
  reachStillConnecting(h);
  h.setWallClock(-9_999_999);
  h.advanceTo(60_000);
  assert.equal(h.mounts(), 0, 'a disconnected shell never mounts Custom Home');
  assert.equal(h.stateEnumerations(), 0, 'late discovery does not enumerate HA states while disconnected');
  assert.equal(h.activeTimers(1_000).length, 0, 'the monotonic 60-second deadline stops late discovery work despite wall-clock rollback');
}

{
  const h = harness();
  h.start();
  h.advanceTo(2_000);
  h.context.document.visibilityState = 'hidden';
  h.listeners.get('visibilitychange')();
  h.makeConnected();
  h.advanceTo(12_000);
  assert.equal(h.mounts(), 0, 'hiding before the initial window cancels the fast discovery probe');
  h.context.document.visibilityState = 'visible';
  h.listeners.get('visibilitychange')();
  assert.equal(h.activeTimers(1_000).length, 1, 'visible resume restarts one bounded late discovery probe after an interrupted fast probe');
  h.advanceTo(13_000);
  assert.equal(h.mounts(), 1, 'the bounded resume probe can mount once Home Assistant is healthy');
}

for (const [label, cancel] of [
  ['route change', (h) => { h.context.location.hash = '#cameras'; h.listeners.get('hashchange')(); }],
  ['stale iframe', (h) => { h.panel.contentDocument = { querySelector() { return null; }, querySelectorAll() { return []; } }; }],
  ['authentication route', (h) => { h.panel.contentWindow.location.pathname = '/auth/authorize'; h.childListeners.get('location-changed')(); }],
  ['pagehide', (h) => h.listeners.get('pagehide')()],
]) {
  const h = harness();
  reachStillConnecting(h);
  cancel(h);
  h.makeConnected();
  h.advanceTo(20_000);
  assert.equal(h.mounts(), 0, `${label} cancels late discovery before it can mount`);
  assert.equal(h.activeTimers(1_000).length, 0, `${label} leaves no late discovery timer`);
}

{
  const h = harness();
  reachStillConnecting(h);
  h.context.document.visibilityState = 'hidden';
  h.listeners.get('visibilitychange')();
  assert.equal(h.activeTimers(1_000).length, 0, 'hidden pages stop late discovery work');
  h.context.document.visibilityState = 'visible';
  h.listeners.get('visibilitychange')();
  h.listeners.get('visibilitychange')();
  assert.equal(h.activeTimers(1_000).length, 1, 'visibility resume starts one bounded probe, without duplicate controllers');
  h.makeConnected();
  h.advanceTo(13_000);
  assert.equal(h.mounts(), 1, 'a visible, still-current Home can recover through the bounded resume probe');
}

console.log('Home late bootstrap: 12-second fallback, bounded recovery, cancellation, visibility, and disconnected-state guards passed');
