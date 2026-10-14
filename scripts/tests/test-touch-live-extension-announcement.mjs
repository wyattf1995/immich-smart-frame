import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync("frameos/touchframe/src/main/assets/frame-live-camera/background.js", "utf8");
const retries = [];
let calls = 0;
const context = {
  browser: { runtime: {
    getURL: path => `moz-extension://opaque/${path}`,
    sendNativeMessage: async (_app, value) => {
      calls += 1;
      assert.equal(value.url, "moz-extension://opaque/live.html");
      return { accepted: calls > 1 };
    },
  } },
  setTimeout: callback => { retries.push(callback); return retries.length; },
};
vm.createContext(context);
vm.runInContext(source, context);
await new Promise(resolve => setImmediate(resolve));
assert.equal(calls, 1);
assert.equal(retries.length, 1);
retries.shift()();
await new Promise(resolve => setImmediate(resolve));
assert.equal(calls, 2);
assert.equal(retries.length, 0);
console.log("touch live extension announcement: PASS");
