import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('./profile.mjs', import.meta.url), 'utf8');

function blockAfter(marker, length = 3_500) {
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `missing ${marker}`);
  return source.slice(start, start + length);
}

test('optimized mode is explicit while the default waits retain legacy DOM and width checks', () => {
  assert.match(source, /const optimizedViews = process\.argv\.includes\('--optimized-views'\);/);
  assert.match(source, /if \(!optimizedViews\) await pages\.companion\.page\.locator\('#preferences li'\)\.first\(\)\.waitFor/);
  assert.match(source, /parseFloat\(meter\.style\.width\) > 0/);
  assert.match(source, /page\.waitForFunction\(browserAudioMeterReady, optimizedViews\)/);
  assert.match(source, /new DOMMatrixReadOnly\(transform\)/);
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
  assert.match(optimized, /companion-expand-1000/);
  assert.match(optimized, /closedAfterExpansion/);
});

test('optimized stress retains the same declared four-tab CPU-throttled workload', () => {
  assert.match(source, /multipleTabs: 4/);
  assert.match(source, /cpuSlowdown: 4/);
  assert.match(source, /'companion-stress-1000-30s', 30/);
  assert.match(source, /'birds-stress-20hz-30s', 30/);
  assert.match(source, /'art-continuous-125s', 125/);
});

function browserCallback() {
  const start = source.indexOf('function browserAudioMeterReady(optimized)');
  const end = source.indexOf('\n\nfunction instrument()', start);
  assert.notEqual(start, -1, 'browser callback must remain source-owned');
  assert.notEqual(end, -1, 'browser callback must remain self-contained');
  return source.slice(start, end);
}

function evaluateMeter({ meter, transform = 'none', matrix } = {}) {
  const context = {
    document: { querySelector: selector => selector === '#audioMeterLevel' ? meter : null },
    getComputedStyle: () => ({ transform }),
    DOMMatrixReadOnly: class {
      constructor(value) {
        if (!matrix || value !== transform) throw new TypeError('invalid matrix');
        this.a = matrix.a;
      }
    },
    Number,
    parseFloat,
  };
  vm.runInNewContext(`${browserCallback()}; globalThis.callback = browserAudioMeterReady`, context);
  return context.callback;
}

test('serialized Bird callback accepts positive scaleX matrices and rejects null, zero, and malformed meters', () => {
  const empty = evaluateMeter();
  assert.equal(empty(true), false);

  const legacy = evaluateMeter({ meter: { style: { width: '37%' } } });
  assert.equal(legacy(false), true, 'baseline callback retains its inline-width path');

  const positive = evaluateMeter({ meter: { style: { width: '' } }, transform: 'matrix(0.37, 0, 0, 1, 0, 0)', matrix: { a: 0.37 } });
  assert.equal(positive(true), true, 'optimized callback accepts a transform with zero translation');

  const zero = evaluateMeter({ meter: { style: { width: '' } }, transform: 'matrix(0, 0, 0, 1, 0, 0)', matrix: { a: 0 } });
  assert.equal(zero(true), false);

  const malformed = evaluateMeter({ meter: { style: { width: '' } }, transform: 'matrix(bad)' });
  assert.equal(malformed(true), false);
});
