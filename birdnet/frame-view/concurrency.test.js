"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const sourcePath = path.join(__dirname, "index.html");
const source = fs.readFileSync(sourcePath, "utf8");
const extract = (start, end) => {
  const match = source.match(new RegExp(`${start}[\\s\\S]*?${end}`));
  assert.ok(match, `extract ${start}`);
  return match[0].replace(new RegExp(`${end}$`), "");
};
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
};

test("BirdNET coalesces same unresolved attribution species", async () => {
  const functionSource = extract("        function loadAttribution\\(scientificName\\) \\{", "\\n\\n        function renderAttribution");
  const calls = [];
  const pending = [];
  const context = {
    disposed: false,
    document: { hidden: false },
    state: { attributionSpecies: "", attribution: null },
    attributionRetry: null,
    IMAGE_RETRY_MS: 60000,
    window: { clearTimeout() {}, setTimeout() { return 1; } },
    els: { heroCredit: { textContent: "" } },
    renderAttribution() {},
    fetchJson(url) { const request = deferred(); calls.push(url); pending.push(request); return request.promise; },
  };
  vm.runInNewContext(`let attributionRequest = null;\n${functionSource}\n;globalThis.load = loadAttribution;`, context, { filename: sourcePath });
  const first = context.load("Turdus migratorius");
  const second = context.load("Turdus migratorius");
  assert.strictEqual(first, second);
  assert.deepEqual(calls, ["/api/v2/media/species-image/info?name=Turdus%20migratorius"]);
  pending[0].resolve({ authorName: "Robin photographer" });
  await first;
  assert.equal(context.state.attribution.authorName, "Robin photographer");
});

test("BirdNET coalesces one range period and aborts an obsolete period", async () => {
  const cancelSource = extract("        function cancelRangeRequest\\(keepPeriod = null\\) \\{", "\\n\\n        function refreshRange");
  const refreshSource = extract("        function refreshRange\\(period = state.speciesPeriod\\) \\{", "\\n\\n        async function refreshFast");
  const calls = [];
  const context = {
    AbortController,
    Promise,
    disposed: false,
    document: { hidden: false },
    activeGeneration: 1,
    state: { rangeSummaries: { "7-days": null, "30-days": null }, speciesPeriod: "7-days", rangeLoadingPeriod: "7-days", summary: null, renderKeys: { species: "" } },
    isRangePeriod: period => period === "7-days" || period === "30-days",
    periodRange: period => ({ start: `${period}-start`, end: `${period}-end` }),
    summaryItems: result => result.items,
    render() {},
    fetchJson(url, signal) { const request = deferred(); calls.push({ url, signal, request }); return request.promise; },
  };
  vm.runInNewContext(`let rangeRequest = null;\n${cancelSource}\n${refreshSource}\n;globalThis.refresh = refreshRange;`, context, { filename: sourcePath });
  const first = context.refresh("7-days");
  const duplicate = context.refresh("7-days");
  assert.strictEqual(first, duplicate);
  assert.equal(calls.length, 1);

  context.state.speciesPeriod = "30-days";
  const replacement = context.refresh("30-days");
  assert.equal(calls.length, 2);
  assert.equal(calls[0].signal.aborted, true, "a newly selected period aborts obsolete transport");
  calls[1].request.resolve({ items: ["current"] });
  await replacement;
  assert.deepEqual(context.state.rangeSummaries["30-days"], ["current"]);
});
