import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../companion/index.html', import.meta.url), 'utf8');
const script = source.split('<script>\n')[1]?.split('\n</script>')[0];
assert.ok(script, 'companion inline script must be present');
const pageSize = Number(script.match(/preferencePageSize=(\d+)/)?.[1]);
const preferenceWindowSource = script.match(/function preferenceWindow\([^\n]+\}/)?.[0];
assert.equal(pageSize, 50, 'saved choices must stay bounded to 50 rows per page');
assert.ok(preferenceWindowSource, 'preferenceWindow must remain a source-owned pagination helper');
const sandbox = { preferencePageSize: pageSize };
vm.runInNewContext(`${preferenceWindowSource}; globalThis.preferenceWindow=preferenceWindow`, sandbox);
const preferenceWindow = sandbox.preferenceWindow;
const windowFor = (total, page) => JSON.parse(JSON.stringify(preferenceWindow(total, page)));

test('preference pages render at most fifty rows and preserve a final remainder', () => {
  assert.deepEqual(windowFor(0, 0), { page: 0, pageCount: 0, start: 0, end: 0 });
  assert.deepEqual(windowFor(1_000, 0), { page: 0, pageCount: 20, start: 0, end: 50 });
  assert.deepEqual(windowFor(1_000, 19), { page: 19, pageCount: 20, start: 950, end: 1_000 });
  assert.deepEqual(windowFor(51, 1), { page: 1, pageCount: 2, start: 50, end: 51 });
});

test('preference pages clamp after an Undo removes the final row', () => {
  assert.deepEqual(windowFor(50, 1), { page: 0, pageCount: 1, start: 0, end: 50 });
  assert.deepEqual(windowFor(100, 9), { page: 1, pageCount: 2, start: 50, end: 100 });
});

test('saved choices remain lazy, selected-device guarded, and page-sliced', () => {
  assert.match(script, /\$\('preferencesDetails'\)\.ontoggle=.*ensurePreferences\(\)/,
    'opening Saved photo choices must start its first load');
  assert.match(script, /if\(!id\|\|!\$\('preferencesDetails'\)\.open/, 
    'the preferences request must be refused while details is closed');
  assert.match(script, /if\(request!==preferencesRequest\|\|id!==selected\)return/,
    'a late response must not overwrite a different selected frame');
  assert.match(script, /preferences\.slice\(window\.start,window\.end\)/,
    'the DOM renderer must consume only the current page');
  assert.doesNotMatch(script, /async function loadPreferences\(/,
    'the prior eager preference loader must not return');
});

test('Undo removes only the current cached row and refills without a reload', () => {
  const remove = script.match(/function removePreference\([^\n]+\}/)?.[0];
  assert.ok(remove, 'Undo row removal helper must remain present');
  assert.match(remove, /preferences\.splice\(index,1\)/);
  assert.match(remove, /renderPreferences\(focusAsset\)/);
  assert.doesNotMatch(remove, /api\(/);
});

const cacheGuardSource = script.match(/function preferenceCacheIsCurrent\([^\n]+\nfunction invalidatePreferences\([^\n]+/)?.[0];
assert.ok(cacheGuardSource, 'preference cache ownership helpers must remain source-owned');
function cacheGuard(selected, preferencesDevice, preferencesRequest, preferencesLoading) {
  const sandbox = { selected, preferencesDevice, preferencesRequest, preferencesLoading };
  vm.runInNewContext(`${cacheGuardSource}; globalThis.snapshot=()=>({selected,preferencesDevice,preferencesRequest,preferencesLoading}); globalThis.invalidate=invalidatePreferences`, sandbox);
  return sandbox;
}

test('feedback invalidates an in-flight preference GET before applying Undo', () => {
  const cache = cacheGuard('frame-one', 'frame-one', 12, true);
  const requestBeforeFeedback = cache.snapshot().preferencesRequest;
  assert.equal(cache.invalidate('frame-one'), true);
  assert.deepEqual(JSON.parse(JSON.stringify(cache.snapshot())), {
    selected: 'frame-one', preferencesDevice: 'frame-one', preferencesRequest: 13, preferencesLoading: false,
  });
  assert.equal(requestBeforeFeedback === cache.snapshot().preferencesRequest && 'frame-one' === cache.snapshot().selected, false,
    'the pre-feedback GET cannot publish after the accepted POST advances the request token');
});

test('a late POST from another selected frame cannot invalidate current cached preferences', () => {
  const cache = cacheGuard('frame-two', 'frame-two', 29, true);
  assert.equal(cache.invalidate('frame-one'), false);
  assert.deepEqual(JSON.parse(JSON.stringify(cache.snapshot())), {
    selected: 'frame-two', preferencesDevice: 'frame-two', preferencesRequest: 29, preferencesLoading: true,
  });
  assert.match(script, /const focusDevice=selected;requestAnimationFrame\(\(\)=>\{if\(!preferenceCacheIsCurrent\(focusDevice\)\)return;/,
    'a queued Undo focus action must stop after a device switch');
});
