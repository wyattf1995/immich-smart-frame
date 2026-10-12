import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../../examples/frameos/frameos-home-forecast.js", import.meta.url), "utf8");
let now = Date.UTC(2026, 9, 12, 12);
const timers = new Map(); let nextTimer = 0;
const context = { window: { setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, { callback, delay, cleared: false }); return id; }, clearTimeout(id) { const timer = timers.get(id); if (timer) timer.cleared = true; } } };
vm.runInNewContext(source, context, { filename: "frameos-home-forecast.js" });
const api = context.window.FrameOSHomeForecast;
assert.ok(api?.create, "forecast API is available");

const flush = async () => { await Promise.resolve(); await Promise.resolve(); };
const payload = (day = "2026-10-12") => [
  { datetime: day, condition: "sunny", temperature: 22, templow: 12, precipitation_probability: 5 },
  { datetime: "2026-10-13", condition: "rainy", temperature: 18, templow: 9, precipitation_probability: 80 },
  { datetime: "2026-10-14", condition: "cloudy", temperature: 17, templow: 8, precipitation_probability: 10 },
  { datetime: "2026-10-15", condition: "fog", temperature: 15, templow: 6, precipitation_probability: 0 },
];

let calls = 0, updates = 0;
const controller = api.create({ readForecast: () => { calls += 1; return Promise.resolve(payload()); }, temperatureUnit: () => "°C", now: () => now, timers: context.window, onUpdate: () => { updates += 1; } });
await controller.refreshIfNeeded();
assert.equal(calls, 0, "inactive controller never fetches");
controller.setActive(true);
await controller.refreshIfNeeded(); await flush();
let view = controller.snapshot();
assert.equal(view.forecastStatus, "ready");
assert.equal(view.forecast.days.length, 3, "forecast is bounded to three daily cards");
assert.equal(view.forecast.temperatureUnit, "°C");
assert.ok(updates >= 3, "loading and result notify the renderer");
await controller.refreshIfNeeded(); assert.equal(calls, 1, "successful data is cached for thirty minutes");
now += 30 * 60 * 1000; await controller.refreshIfNeeded(); await flush(); assert.equal(calls, 2, "refresh resumes at the success TTL");
controller.setActive(false); view = controller.snapshot(); assert.equal(view.forecast.days.length, 3, "deactivation retains cached controller data");
const inactiveCalls = calls; await controller.refreshIfNeeded(); assert.equal(calls, inactiveCalls, "disabled state prevents new fetches");

const sanitized = api.__test.sanitize([
  { datetime: "2026-10-11", condition: "sunny", temperature: 22 },
  { datetime: "2026-10-12", condition: "not-real", temperature: 999, templow: -999, precipitation_probability: 101 },
  { datetime: "2026-10-13", condition: "rainy", temperature: "19", templow: "", precipitation_probability: "80" },
  { datetime: "2026-10-13", condition: "sunny", temperature: 20 },
  { datetime: "2026-10-14", condition: "cloudy", temperature: 17 },
  { datetime: "2026-10-15T16:00:00+00:00", condition: "fog", temperature: 15 },
], "°C", now);
assert.equal(sanitized.days.length, 3, "past, malformed, and duplicate daily records are skipped");
assert.equal(sanitized.days[0].temperature, 19, "finite bounded numeric strings are accepted");
assert.equal(sanitized.days[0].templow, null, "missing temperatures do not coerce to zero");
assert.match(sanitized.days[2].datetime, /^2026-10-1[45]$/, "Home Assistant ISO timestamps become a bounded local calendar day");
assert.equal(api.__test.sanitize(payload(), "kelvin", now), null, "unsupported temperature units are unavailable");

let recursiveCalls = 0; let recursive, reentered = false;
recursive = api.create({ readForecast: () => { recursiveCalls += 1; return Promise.resolve(payload()); }, temperatureUnit: () => "°F", now: () => now, timers: context.window, onUpdate: () => { if (!reentered) { reentered = true; recursive.refreshIfNeeded(); } } });
recursive.setActive(true); await flush(); await flush();
assert.equal(recursiveCalls, 1, "a synchronous renderer refresh cannot recurse before the request is fenced");
reentered = false; now += 30 * 60 * 1000;
await recursive.refreshIfNeeded(); await flush();
assert.equal(recursiveCalls, 2, "a later refresh remains fenced before it synchronously redraws");
recursive.destroy();

let resolveLate;
const timedOut = api.create({ readForecast: () => new Promise((resolve) => { resolveLate = resolve; }), temperatureUnit: () => "°F", now: () => now, timers: context.window });
timedOut.setActive(true); timedOut.refreshIfNeeded(); await flush();
[...timers.values()].filter((timer) => timer.delay === 10_000 && !timer.cleared).at(-1).callback();
resolveLate(payload()); await flush();
assert.equal(timedOut.snapshot().forecastStatus, "unavailable", "a late success after timeout cannot overwrite unavailable state");
timedOut.destroy();

let rejectFetch; let lateCalls = 0;
const late = api.create({ readForecast: () => { lateCalls += 1; return new Promise((resolve, reject) => { rejectFetch = reject; }); }, temperatureUnit: () => "°F", now: () => now, timers: context.window });
late.setActive(true); late.refreshIfNeeded(); await flush();
const timeout = [...timers.values()].find((timer) => timer.delay === 10_000 && !timer.cleared);
timeout.callback(); await flush();
assert.equal(late.snapshot().forecastStatus, "unavailable", "ten second logical timeout ends loading");
await late.refreshIfNeeded(); assert.equal(lateCalls, 1, "an error waits sixty seconds before retry");
now += 60_000; late.refreshIfNeeded(); await flush(); assert.equal(lateCalls, 2, "retry resumes after the error TTL");
late.setActive(false); rejectFetch(new Error("late")); await flush();
assert.equal(late.snapshot().forecastStatus, "unavailable", "late completion after deactivation cannot revive state");

now += 61 * 60 * 1000;
assert.equal(controller.snapshot().forecast, null, "forecast becomes unavailable after one hour stale");
controller.destroy(); late.destroy();
assert.doesNotMatch(source, /fetch\(|hass|token|Authorization/i, "controller accepts only its read callback and never handles Home Assistant credentials");
console.log("frameos Home forecast controller tests passed");
