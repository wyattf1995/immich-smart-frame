import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';

const root = new URL('../../', import.meta.url);
const sourceUrl = new URL('companion/index.html', root);
const testUrl = new URL('scripts/tests/companion-preferences-pagination.test.mjs', root);
const source = await readFile(sourceUrl);
const testHash = createHash('sha256').update(await readFile(testUrl)).digest('hex');
const run = () => spawnSync(process.execPath, ['--test', testUrl.pathname], { cwd: root.pathname, encoding: 'utf8' });
const greenBefore = run();
assert.equal(greenBefore.status, 0, greenBefore.stdout + greenBefore.stderr);
const needle = Buffer.from('preferencePageSize=50');
assert.equal(source.indexOf(needle), source.lastIndexOf(needle), 'expected one page-size source needle');
try {
  await writeFile(sourceUrl, Buffer.from(source.toString().replace('preferencePageSize=50', 'preferencePageSize=1000')));
  const red = run();
  assert.notEqual(red.status, 0, 'unbounded page size must fail the behavior test');
  assert.match(red.stdout + red.stderr, /saved choices must stay bounded to 50 rows per page/);
} finally {
  await writeFile(sourceUrl, source);
}
assert.deepEqual(await readFile(sourceUrl), source, 'source bytes must be restored');
assert.equal(createHash('sha256').update(await readFile(testUrl)).digest('hex'), testHash, 'test bytes must stay unchanged');
const greenAfter = run();
assert.equal(greenAfter.status, 0, greenAfter.stdout + greenAfter.stderr);
console.log(JSON.stringify({ status: 'passed', red: 'preferencePageSize=1000', sourceRestored: true, testsUnchanged: true }));
