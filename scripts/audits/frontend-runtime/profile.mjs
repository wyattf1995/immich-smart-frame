// Audit instrumentation only. Run through the guarded, pinned NAS browser runtime.
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { handleHomeArt, fixtureMetadata as homeMetadata } from './home-art-fixture.mjs';
import { handleCompanionBirds, close as closeFixtures, fixtureMetadata as otherMetadata } from './companion-birds-fixture.mjs';

const require = createRequire('/opt/launchpad-browser-runtime/package.json');
const { chromium } = require('playwright');
const repoRoot = process.cwd();
const output = resolve('output/playwright/runtime-audit');
await mkdir(output, { recursive: true });
const result = { startedAt: new Date().toISOString(), productCommit: '3163d2e8bea938a5d69d656c20fee87df44512ec',
  conditions: { viewport: { width: 1280, height: 800 }, cpuSlowdown: 4, headless: true, trustedSyntheticLoopback: true,
    physicalDevice: false, networkThrottle: 'none; loopback fixtures isolate runtime from network', multipleTabs: 4,
    frameMetric: 'Visible requestAnimationFrame scheduling gaps; not physical display drops',
    heapMetric: 'Forced-GC used heap and snapshots, instrumentation included; growth alone is not a leak' },
  fixtures: { home: homeMetadata, other: otherMetadata }, requests: {}, consoleErrors: [], measurements: [], views: [] };
let browser, context;
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  result.requests[url.pathname] = (result.requests[url.pathname] || 0) + 1;
  try {
    if (url.pathname === '/empty') { res.writeHead(200, { 'content-type': 'text/html' }); res.end('<!doctype html><title>Audit teardown</title><main>View disposed</main>'); return; }
    if (await handleHomeArt(req, res, url, repoRoot)) return;
    if (await handleCompanionBirds(req, res, url, repoRoot)) return;
    res.writeHead(404); res.end('Not in fixture');
  } catch (error) { result.consoleErrors.push({ server: String(error) }); if (!res.headersSent) res.writeHead(500); res.end('Fixture failure'); }
});
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const save = (name, data) => writeFile(join(output, name), JSON.stringify(data, null, 2));
const log = message => console.log(new Date().toISOString(), message);

function instrument() {
  const a = window.__runtimeAudit = { longTasks: [], events: [], raf: { count: 0, over25: 0, over50: 0, max: 0 },
    mutations: { records: 0, added: 0, removed: 0, activityAdded: 0 }, began: performance.now() };
  try { new PerformanceObserver(list => { for (const x of list.getEntries()) a.longTasks.push({ start: x.startTime, duration: x.duration, name: x.name }); }).observe({ type: 'longtask', buffered: false }); } catch {}
  try { new PerformanceObserver(list => { for (const x of list.getEntries()) a.events.push({ name: x.name, start: x.startTime, duration: x.duration, processing: x.processingEnd - x.processingStart, inputDelay: x.processingStart - x.startTime, interactionId: x.interactionId }); }).observe({ type: 'event', durationThreshold: 16, buffered: false }); } catch {}
  let previous = 0;
  function frame(now) { if (!document.hidden && previous) { const gap = now - previous; a.raf.count++; a.raf.over25 += gap > 25 ? 1 : 0; a.raf.over50 += gap > 50 ? 1 : 0; a.raf.max = Math.max(a.raf.max, gap); } previous = document.hidden ? 0 : now; requestAnimationFrame(frame); }
  requestAnimationFrame(frame);
  const observe = () => new MutationObserver(records => { for (const r of records) {
    a.mutations.records++; a.mutations.added += r.addedNodes.length; a.mutations.removed += r.removedNodes.length;
    if (r.target.id === 'activityBars' || r.target.classList?.contains('activity-bars')) a.mutations.activityAdded += r.addedNodes.length;
  }}).observe(document.documentElement, { subtree: true, childList: true });
  if (document.documentElement) observe(); else document.addEventListener('DOMContentLoaded', observe, { once: true });
}
async function cdpMetrics(cdp) {
  const [p, d, h] = await Promise.all([cdp.send('Performance.getMetrics'), cdp.send('Memory.getDOMCounters'), cdp.send('Runtime.getHeapUsage')]);
  return { performance: Object.fromEntries(p.metrics.map(x => [x.name, x.value])), dom: d, heap: h };
}
async function snapshot(cdp, name) {
  await cdp.send('HeapProfiler.collectGarbage');
  const chunks = []; const listener = x => chunks.push(x.chunk);
  cdp.on('HeapProfiler.addHeapSnapshotChunk', listener);
  try { await cdp.send('HeapProfiler.takeHeapSnapshot', { reportProgress: false, captureNumericValue: false }); }
  finally { cdp.off('HeapProfiler.addHeapSnapshotChunk', listener); }
  const raw = chunks.join('');
  await writeFile(join(output, `${name}.heapsnapshot.gz`), gzipSync(raw));
  const heap = JSON.parse(raw), fields = heap.snapshot.meta.node_fields, width = fields.length;
  const detached = fields.indexOf('detachedness'), size = fields.indexOf('self_size'), type = fields.indexOf('type');
  const types = heap.snapshot.meta.node_types[type];
  let detachedNodes = 0, detachedBytes = 0, nativeNodes = 0;
  for (let i = 0; i < heap.nodes.length; i += width) {
    if (types[heap.nodes[i + type]] === 'native') nativeNodes++;
    if (detached >= 0 && heap.nodes[i + detached] === 2) { detachedNodes++; detachedBytes += heap.nodes[i + size]; }
  }
  return { filename: `${name}.heapsnapshot.gz`, nodeCount: heap.snapshot.node_count, detachedNodes, detachedBytes, nativeNodes, metrics: await cdpMetrics(cdp) };
}
async function startTrace(cdp) {
  await cdp.send('Tracing.start', { categories: 'devtools.timeline,v8,blink.user_timing', transferMode: 'ReturnAsStream' });
}
async function endTrace(cdp, name) {
  const completed = new Promise(resolve => cdp.once('Tracing.tracingComplete', resolve));
  await cdp.send('Tracing.end'); const { stream } = await completed;
  const chunks = [];
  while (true) { const part = await cdp.send('IO.read', { handle: stream }); chunks.push(part.base64Encoded ? Buffer.from(part.data, 'base64').toString() : part.data); if (part.eof) break; }
  await cdp.send('IO.close', { handle: stream });
  const raw = chunks.join(''); await writeFile(join(output, `${name}.trace.json.gz`), gzipSync(raw));
  const trace = JSON.parse(raw); const byName = {};
  for (const event of trace.traceEvents) if (event.ph === 'X' && event.dur) {
    const key = `${event.pid}:${event.tid}:${event.name}`;
    const v = byName[key] ||= { pid: event.pid, tid: event.tid, name: event.name, count: 0, durationMs: 0, maxMs: 0 };
    v.count++; v.durationMs += event.dur / 1000; v.maxMs = Math.max(v.maxMs, event.dur / 1000);
  }
  await save(`${name}.trace-summary.json`, Object.values(byName));
  return `${name}.trace.json.gz`;
}
async function resetObserver(page) {
  await page.evaluate(() => { const a = window.__runtimeAudit; a.longTasks = []; a.events = []; a.raf = { count: 0, over25: 0, over50: 0, max: 0 }; a.mutations = { records: 0, added: 0, removed: 0, activityAdded: 0 }; a.began = performance.now(); });
}
async function action(page, kind, iteration) {
  if (kind === 'home') {
    if (iteration % 4 === 0) await page.locator('[data-adjust="temperature:1"]').click();
    else if (iteration % 4 === 1 && await page.locator('[data-apply]').isEnabled()) await page.locator('[data-apply]').click();
    else if (iteration % 4 === 2) await page.locator('[data-adjust="temperature:-1"]').click();
    else if (await page.locator('[data-cancel]').isEnabled()) await page.locator('[data-cancel]').click();
  } else if (kind === 'art') {
    await page.locator('.art-shell').press(iteration % 5 === 4 ? 'ArrowLeft' : 'ArrowRight');
  } else if (kind === 'companion') {
    const operation = iteration % 5;
    if (operation === 0) await page.locator('#frameSelect').selectOption(iteration % 2 ? 'fixture-main' : 'fixture-2');
    if (operation === 1) await page.getByText('Saved photo choices', { exact: true }).click();
    if (operation === 2) { const choices = page.locator('#preferences'); if (await choices.isVisible()) { await choices.locator('button').first().click(); await page.mouse.wheel(0, 650); } }
    if (operation === 3) { await page.locator('#pause').click(); }
    if (operation === 4) { await page.locator('#quietEnabled').check(); await page.locator('#settings button[type="submit"]').click(); }
  } else if (kind === 'birds') {
    const periods = page.locator('[data-species-period]'); if (await periods.count()) await periods.nth(iteration % (await periods.count())).click();
    const filters = page.locator('[data-review-filter]'); if (await filters.count()) await filters.nth(iteration % (await filters.count())).click();
    await page.mouse.wheel(0, iteration % 2 ? -500 : 500);
  }
}
async function measure(page, cdp, kind, label, seconds, options = {}) {
  log(`MEASURE ${label} ${seconds}s`);
  await page.bringToFront(); await page.evaluate(() => scrollTo(0, 0));
  const before = await snapshot(cdp, `${label}-before`);
  await resetObserver(page); if (options.trace) await startTrace(cdp);
  const beforeMetrics = await cdpMetrics(cdp), start = Date.now(), actions = [];
  while (Date.now() - start < seconds * 1000) {
    const actionStart = Date.now();
    try { await action(page, kind, actions.length); actions.push({ elapsedMs: Date.now() - actionStart, success: true }); }
    catch (error) { actions.push({ elapsedMs: Date.now() - actionStart, success: false, error: String(error) }); }
    await sleep(Math.max(50, 2500 - (Date.now() - actionStart)));
  }
  const elapsedMs = Date.now() - start;
  const observed = await page.evaluate(() => ({ ...window.__runtimeAudit, visible: !document.hidden,
    elementCount: document.querySelectorAll('*').length, totalDomNodes: (() => { const walk = document.createTreeWalker(document, NodeFilter.SHOW_ALL); let n = 0; while (walk.nextNode()) n++; return n; })(),
    bodyText: document.body.innerText.slice(0, 2000) }));
  const afterMetrics = await cdpMetrics(cdp);
  const trace = options.trace ? await endTrace(cdp, label) : null;
  const after = await snapshot(cdp, `${label}-after`);
  const record = { kind, label, secondsRequested: seconds, elapsedMs, actions, observed, beforeMetrics, afterMetrics, before, after, trace,
    longTaskCount: observed.longTasks.length, blockingMs: observed.longTasks.reduce((sum, x) => sum + Math.max(0, x.duration - 50), 0),
    heapGrowthBytes: after.metrics.heap.usedSize - before.metrics.heap.usedSize };
  result.measurements.push(record); await save(`${label}.json`, record); await save('results.partial.json', result);
  await page.screenshot({ path: join(output, `${label}.png`), fullPage: false });
  log(`RESULT ${label} longTasks=${record.longTaskCount} blockingMs=${record.blockingMs.toFixed(1)} DOM=${observed.totalDomNodes} heapDelta=${record.heapGrowthBytes}`);
  return record;
}

try {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ headless: true, chromiumSandbox: false });
  result.browserVersion = browser.version();
  context = await browser.newContext({ viewport: result.conditions.viewport, deviceScaleFactor: 1, reducedMotion: 'no-preference' });
  await context.addInitScript(instrument);
  // Block any accidental external requests. Fixture source and all data stay local.
  await context.route('**/*', route => route.request().url().startsWith(base + '/') ? route.continue() : route.abort());
  const pages = {};
  for (const [kind, route] of [['home', '/home'], ['art', '/art/'], ['companion', '/companion'], ['birds', '/birds']]) {
    const page = await context.newPage(), cdp = await context.newCDPSession(page);
    await cdp.send('Performance.enable'); await cdp.send('HeapProfiler.enable');
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
    page.setDefaultTimeout(5000);
    page.on('pageerror', error => result.consoleErrors.push({ kind, error: String(error) }));
    await page.goto(base + route, { waitUntil: 'domcontentloaded' }); pages[kind] = { page, cdp, route };
  }
  await pages.home.page.locator('#custom-home:not([hidden]) [data-adjust="temperature:1"]').waitFor({ timeout: 20000 });
  await pages.art.page.locator('.artwork').waitFor();
  await pages.companion.page.locator('#preferences li').first().waitFor();
  await pages.birds.page.locator('#speciesGrid').waitFor();
  for (const kind of ['home', 'art', 'companion', 'birds']) {
    for (let i = 0; i < 5; i++) { await action(pages[kind].page, kind, i); await sleep(300); }
    await pages[kind].page.screenshot({ path: join(output, `${kind}-smoke.png`), fullPage: false });
  }
  await sleep(3000);
  result.continuousUseStartedAt = new Date().toISOString();
  for (const kind of ['home', 'companion', 'birds', 'art']) {
    const { page, cdp } = pages[kind];
    const baseline = await measure(page, cdp, kind, `${kind}-baseline-30s`, 30, { trace: true });
    if (kind === 'companion') {
      await context.request.post(base + '/fixture/config', { data: { preferences: 1000 } });
      await page.reload({ waitUntil: 'domcontentloaded' }); await page.locator('#preferences li').nth(999).waitFor();
      await measure(page, cdp, kind, 'companion-stress-1000-30s', 30, { trace: true });
    } else if (kind === 'birds') {
      await context.request.post(base + '/fixture/config', { data: { audioHz: 20, summaryCount: 500, recentCount: 500 } });
      await page.reload({ waitUntil: 'domcontentloaded' }); await sleep(2000);
      await measure(page, cdp, kind, 'birds-stress-20hz-30s', 30, { trace: true });
      await context.request.post(base + '/fixture/config', { data: { audioHz: 2, summaryCount: 200, recentCount: 10 } });
    } else if (kind === 'home') {
      await page.frames().find(f => f.url().includes('/frameos-touchscreen/home'))?.evaluate(() => { if (window.__fixture) { window.__fixture.setStatesIdentityChurn(true); } });
      await measure(page, cdp, kind, 'home-state-churn-30s', 30, { trace: true });
    } else {
      await measure(page, cdp, kind, 'art-continuous-125s', 125, { trace: false });
    }
    const beforeNavigation = await snapshot(cdp, `${kind}-before-navigation`);
    await page.goto(base + '/empty', { waitUntil: 'load' }); await sleep(2000);
    const afterNavigation = await snapshot(cdp, `${kind}-after-navigation`);
    result.views.push({ kind, beforeNavigation, afterNavigation });
  }
  result.continuousUseEndedAt = new Date().toISOString();
  result.continuousUseSeconds = (Date.parse(result.continuousUseEndedAt) - Date.parse(result.continuousUseStartedAt)) / 1000;
  if (result.continuousUseSeconds < 300) throw Error('Less than five minutes continuous use');
  const failedActions = result.measurements.flatMap(m => m.actions.filter(a => !a.success).map(a => ({ view: m.label, ...a })));
  result.failedActions = failedActions;
  if (failedActions.length) throw Error(`${failedActions.length} fixture interactions failed; retain evidence for correction`);
  if (result.consoleErrors.length) throw Error(`Browser/fixture errors: ${JSON.stringify(result.consoleErrors)}`);
  result.status = 'complete';
} catch (error) {
  for (const page of context?.pages() || []) { try { await page.screenshot({ path: join(output, `failure-${context.pages().indexOf(page)}.png`) }); } catch {} }
  result.status = 'failed'; result.error = String(error); console.error(error); process.exitCode = 1;
} finally {
  await context?.close().catch(() => {}); await browser?.close().catch(() => {}); closeFixtures();
  server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  result.endedAt = new Date().toISOString(); result.resourcesClosed = true;
  await save('results.json', result);
  try { const identity = await readFile('AUDIT-SOURCE-IDENTITY.json', 'utf8'); await writeFile(join(output, 'source-identity.json'), identity); } catch {}
  execFileSync('tar', ['-czf', 'output/playwright/runtime-audit.tar.gz', '-C', 'output/playwright', 'runtime-audit']);
  const archive = await readFile('output/playwright/runtime-audit.tar.gz');
  log(`ARCHIVE sha256=${createHash('sha256').update(archive).digest('hex')} bytes=${archive.length} status=${result.status}`);
}
