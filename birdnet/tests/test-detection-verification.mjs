import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const testDir = path.dirname(fileURLToPath(import.meta.url));
const viewPath = path.join(testDir, "..", "frame-view", "index.html");
const source = fs.readFileSync(viewPath, "utf8");

function extractFunction(name) {
  const marker = `function ${name}(`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `missing ${name} in frame view`);
  const bodyStart = source.indexOf("{", start);
  let depth = 0;
  for (let index = bodyStart; index < source.length; index += 1) {
    if (source[index] === "{") depth += 1;
    if (source[index] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(start, index + 1);
    }
  }
  assert.fail(`unterminated ${name} in frame view`);
}

const verificationFunction = extractFunction("verificationStatus");
const latestFunction = extractFunction("latestDisplayDetection");
const helpers = new Function(
  `"use strict"; ${verificationFunction}; ${latestFunction}; return { verificationStatus, latestDisplayDetection };`,
)();

assert.deepEqual(helpers.verificationStatus({ verified: "correct" }), {
  key: "confirmed",
  label: "Confirmed by review",
  shortLabel: "Confirmed",
});
assert.deepEqual(helpers.verificationStatus({ verified: "false_positive" }), {
  key: "rejected",
  label: "Rejected after review",
  shortLabel: "Rejected",
});

for (const detection of [
  {},
  { verified: null },
  { verified: true },
  { verified: "" },
  { verified: "unverified" },
  { verified: "unknown_future_value" },
]) {
  assert.deepEqual(helpers.verificationStatus(detection), {
    key: "candidate",
    label: "Model candidate · needs review",
    shortLabel: "Candidate",
  });
}

const rejected = { id: 3, verified: "false_positive" };
const candidate = { id: 2, verified: "unverified" };
const confirmed = { id: 1, verified: "correct" };
const detections = [rejected, candidate, confirmed];
assert.equal(helpers.latestDisplayDetection(detections), candidate);
assert.deepEqual(detections, [rejected, candidate, confirmed], "hero selection must not mutate raw recent evidence");
assert.equal(helpers.latestDisplayDetection([rejected]), null);
assert.equal(helpers.latestDisplayDetection(null), null);

console.log("PASS: BirdNET detection verification semantics");
