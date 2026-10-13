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

test('preference pages render at most fifty rows and preserve a final remainder', () => {
  assert.deepEqual(preferenceWindow(0, 0), { page: 0, pageCount: 0, start: 0, end: 0 });
  assert.deepEqual(preferenceWindow(1_000, 0), { page: 0, pageCount: 20, start: 0, end: 50 });
  assert.deepEqual(preferenceWindow(1_000, 19), { page: 19, pageCount: 20, start: 950, end: 1_000 });
  assert.deepEqual(preferenceWindow(51, 1), { page: 1, pageCount: 2, start: 50, end: 51 });
});

test('preference pages clamp after an Undo removes the final row', () => {
  assert.deepEqual(preferenceWindow(50, 1), { page: 0, pageCount: 1, start: 0, end: 50 });
  assert.deepEqual(preferenceWindow(100, 9), { page: 1, pageCount: 2, start: 50, end: 100 });
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
