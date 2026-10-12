import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../../examples/frameos/frameos-custom-home.js", import.meta.url), "utf8");
const timers = [];
const context = { window: {} };
context.window.window = context.window;
context.window.setInterval = (callback, delay) => { const timer = { callback, delay, cleared: false }; timers.push(timer); return timer; };
context.window.clearInterval = (timer) => { timer.cleared = true; };
vm.runInNewContext(source, context, { filename: "frameos-custom-home.js" });
const api = context.window.FrameOSCustomHome;
assert.ok(api?.__test, "custom home exposes pure test helpers");
const { clampTemperature, climateModel, modePayload, setpointPayload, isWritable } = api.__test;

assert.equal(clampTemperature(68, { min: 60, max: 80, step: 1 }), 68);
assert.equal(clampTemperature(99, { min: 60, max: 80, step: 1 }), 80, "drafts never exceed the device maximum");
assert.equal(clampTemperature("unknown", { min: 60, max: 80, step: 1 }), null, "unknown values never become zero");

const off = climateModel({ state: "off", attributes: { hvac_modes: ["off", "heat", "cool"], min_temp: 50, max_temp: 90, target_temp_step: 1, temperature: 68 } }, "°F");
assert.equal(off.temperatureEnabled, false, "off disables temperature changes until a mode is explicitly selected");
assert.equal(off.unit, "°F", "the display preserves Home Assistant's configured temperature unit");
assert.equal(modePayload("heat"), JSON.stringify({ hvac_mode: "heat" }), "mode actions use one explicit climate payload");
assert.equal(setpointPayload({ mode: "heat", temperature: 71 }), JSON.stringify({ temperature: 71 }), "single setpoint mode never sends an implied HVAC mode");

const dual = climateModel({ state: "heat_cool", attributes: { hvac_modes: ["off", "heat_cool"], min_temp: 50, max_temp: 90, target_temp_step: 1, target_temp_low: 66, target_temp_high: 72 } }, "°F");
assert.equal(dual.dual, true);
assert.equal(setpointPayload({ mode: "heat_cool", low: 74, high: 70 }), null, "invalid dual setpoints are refused before service calls");
assert.equal(setpointPayload({ mode: "heat_cool", low: 66, high: 72 }), JSON.stringify({ target_temp_low: 66, target_temp_high: 72 }), "dual mode uses low/high service fields");
assert.equal(isWritable({ connected: false, pending: false, state: "heat" }), false, "disconnected dashboards cannot write");
assert.equal(isWritable({ connected: true, pending: true, state: "heat" }), false, "a pending write prevents overlap");
assert.equal(isWritable({ connected: true, pending: false, state: "unknown" }), false, "unknown state cannot write");

assert.match(source, /callService\("climate", "set_temperature"/, "temperature changes use Home Assistant's service API");
assert.match(source, /callService\("climate", "set_hvac_mode"/, "mode changes use Home Assistant's service API");
assert.match(source, /addEventListener\("click"/, "writes require a direct click event");
assert.doesNotMatch(source, /fetch\(/, "the custom view never fetches or handles credentials");
assert.doesNotMatch(source, /localStorage|sessionStorage|document\.cookie/, "the custom view never reads browser identity data");

let serviceCalls = 0;
const root = {
  innerHTML: "",
  replaceChildrenCalls: 0,
  replaceChildren() { this.replaceChildrenCalls += 1; },
  querySelectorAll() { return []; },
  querySelector() { return null; },
};
const handle = api.mount(root, {
  getHass: () => ({ connected: true, config: { unit_system: { temperature: "°F" } }, states: { ["climate.entryway_entryway"]: { state: "heat", attributes: { hvac_modes: ["off", "heat"], min_temp: 50, max_temp: 90, target_temp_step: 1, temperature: 68 } } }, callService() { serviceCalls += 1; } }),
});
assert.equal(serviceCalls, 0, "render ticks are read-only and never make climate writes");
assert.equal(timers.at(-1).delay, 1000, "the only background work is the bounded one-second render tick");
handle.destroy();
assert.equal(timers.at(-1).cleared, true, "destroy cancels the render tick to prevent a detached view race");
assert.equal(root.replaceChildrenCalls, 1, "destroy clears the mounted custom view");
