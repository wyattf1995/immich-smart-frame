import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('./profile.mjs', import.meta.url), 'utf8');

function blockAfter(marker, length = 3_500) {
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `missing ${marker}`);
  return source.slice(start, start + length);
}

test('optimized mode is explicit while the default waits retain legacy DOM and width checks', () => {
  assert.match(source, /const optimizedViews = process\.argv\.includes\('--optimized-views'\);/);
  assert.match(source, /if \(!optimizedViews\) await pages\.companion\.page\.locator\('#preferences li'\)\.first\(\)\.waitFor/);
  assert.match(source, /: parseFloat\(document\.querySelector\('#audioMeterLevel'\)\?\.style\.width\) > 0/);
  assert.match(source, /optimizedViews\s*\n?\s*\? \(\(\) => \{ const value = getComputedStyle\(document\.querySelector\('#audioMeterLevel'\)\)\.transform/);
});

test('source identity accepts only verified product paths and rejects missing or mismatched input', () => {
  assert.match(source, /readFile\(resolve\('AUDIT-SOURCE-IDENTITY\.json'\), 'utf8'\)/);
  assert.match(source, /productIdentityFiles = auditIdentity\.files\.filter/);
  assert.match(source, /!item\.path\.startsWith\('scripts\/audits\/frontend-runtime\/'\)/);
  assert.match(source, /bytes\.length !== item\.bytes/);
  assert.match(source, /source identity mismatch/);
});

test('optimized Companion remains closed until one bounded first expansion and records pagination', () => {
  const optimized = blockAfter("const closed = await page.evaluate");
  assert.match(optimized, /rows !== 0 \|\| closed\.detailsOpen \|\| beforeRequests !== 0/);
  assert.match(optimized, /locator\('#preferencesDetails > summary'\)\.click/);
  assert.match(optimized, /locator\('#preferences li'\)\.nth\(49\)/);
  assert.match(optimized, /availableTotal !== 1000/);
  assert.match(optimized, /afterRequests !== beforeRequests \+ 1/);
  assert.match(optimized, /locator\('#preferencesNext'\)\.click/);
  assert.match(optimized, /Page 2 of 20/);
  assert.match(optimized, /companionOptimizedContract/);
});

test('optimized stress retains the same declared four-tab CPU-throttled workload', () => {
  assert.match(source, /multipleTabs: 4/);
  assert.match(source, /cpuSlowdown: 4/);
  assert.match(source, /'companion-stress-1000-30s', 30/);
  assert.match(source, /'birds-stress-20hz-30s', 30/);
  assert.match(source, /'art-continuous-125s', 125/);
});
