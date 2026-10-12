import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../../examples/frameos/frameos-custom-home.js", import.meta.url), "utf8");
const timers = [];
const context = { window: {} };
context.window.window = context.window;
context.window.setInterval = (callback, delay) => { const timer = { callback, delay, cleared: false }; timers.push(timer); return timer; };
context.window.clearInterval = (timer) => { timer.cleared = true; };
context.window.setTimeout = (callback, delay) => { const timer = { callback, delay, cleared: false, timeout: true }; timers.push(timer); return timer; };
context.window.clearTimeout = (timer) => { timer.cleared = true; };
vm.runInNewContext(source, context, { filename: "frameos-custom-home.js" });
const api = context.window.FrameOSCustomHome;
assert.ok(api?.__test, "custom home exposes pure test helpers");
const { clampTemperature, climateModel, modePayload, setpointPayload, isWritable } = api.__test;

assert.equal(clampTemperature(68, { min: 60, max: 80, step: 1 }), 68);
assert.equal(clampTemperature(99, { min: 60, max: 80, step: 1 }), 80, "drafts never exceed the device maximum");
assert.equal(clampTemperature("unknown", { min: 60, max: 80, step: 1 }), null, "unknown values never become zero");
assert.equal(api.__test.finite(null), null, "null never coerces into zero");
assert.equal(api.__test.finite(""), null, "empty strings never coerce into zero");
assert.equal(api.__test.finite(false), null, "boolean values never coerce into zero");
assert.equal(api.__test.eventTime("not-a-date"), "Time unavailable", "bad calendar dates are rendered safely");
assert.equal(api.__test.eventTime("2026-10-12"), "All day", "all-day calendar items retain their semantics");

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
assert.match(source, /data-nav="home">Home</, "the web dashboard uses an accurate Home rail label");
assert.match(source, /Weather unavailable/, "missing weather is shown honestly rather than fabricated");
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

const makeInteractiveRoot = () => {
  const root = { nodes: [], clockNodes: new Map(), writes: 0, replaceChildrenCalls: 0, _html: "" };
  Object.defineProperty(root, "innerHTML", {
    get() { return this._html; },
    set(markup) {
      this._html = markup; this.writes += 1; this.nodes = []; this.clockNodes = new Map();
      const matches = markup.matchAll(/<button([^>]*)>/g);
      for (const match of matches) {
        const attributes = match[1], listeners = new Map(), dataset = {};
        for (const data of attributes.matchAll(/data-([a-z-]+)(?:="([^"]*)")?/g)) dataset[data[1].replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = data[2] ?? "";
        this.nodes.push({ dataset, disabled: /\sdisabled(?:\s|>|$)/.test(attributes), addEventListener(type, callback) { listeners.set(type, callback); }, click() { return listeners.get("click")?.(); } });
      }
      for (const match of markup.matchAll(/<(span|small)([^>]*)>([^<]*)<\/\1>/g)) {
        const marker = match[2].match(/data-frameos-clock-(time|period|date)/)?.[1];
        if (marker) {
          const node = { _textContent: match[3], assignments: 0 };
          Object.defineProperty(node, "textContent", {
            get() { return this._textContent; },
            set(value) { this.assignments += 1; this._textContent = value; },
          });
          this.clockNodes.set(marker, node);
        }
      }
    },
  });
  root.replaceChildren = () => { root.replaceChildrenCalls += 1; root.nodes = []; root.clockNodes = new Map(); root._html = ""; };
  root.querySelectorAll = (selector) => { const key = selector.match(/^\[data-([^\]]+)\]$/)?.[1]?.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()); return key ? root.nodes.filter((node) => Object.hasOwn(node.dataset, key)) : []; };
  root.querySelector = (selector) => root.clockNodes.get(selector.match(/^\[data-frameos-clock-(time|period|date)\]$/)?.[1]) || root.querySelectorAll(selector)[0] || null;
  return root;
};
const interactiveRoot = makeInteractiveRoot();
let connected = true;
let deferredReject;
const services = [];
const liveHass = {
  get connected() { return connected; },
  config: { unit_system: { temperature: "°F" } },
  states: { "climate.entryway_entryway": { state: "heat", attributes: { hvac_modes: ["off", "heat", "cool", "heat_cool"], min_temp: 60, max_temp: 80, target_temp_step: 1, temperature: 68, current_temperature: 68 } } },
  callService(domain, service, payload) { services.push([domain, service, payload]); return new Promise((_, reject) => { deferredReject = reject; }); },
};
const interactive = api.mount(interactiveRoot, { getHass: () => liveHass });
const initialWrites = interactiveRoot.writes;
timers.find((timer) => timer.delay === 1000 && !timer.cleared)?.callback();
assert.equal(interactiveRoot.writes, initialWrites, "unchanged one-second ticks preserve the mounted controls and focus");
const heatButton = interactiveRoot.querySelectorAll("[data-mode]").find((button) => button.dataset.mode === "cool");
const firstAction = heatButton.click();
await Promise.resolve();
assert.equal(JSON.stringify(services[0]), JSON.stringify(["climate", "set_hvac_mode", { entity_id: "climate.entryway_entryway", hvac_mode: "cool" }]), "a mode click uses an explicit, current service payload");
heatButton.click();
assert.equal(services.length, 1, "pending mode changes cannot overlap");
deferredReject(new Error("offline"));
await firstAction;
connected = false;
interactive.refresh();
const offlineCool = interactiveRoot.querySelectorAll("[data-mode]").find((button) => button.dataset.mode === "cool");
offlineCool.click();
assert.equal(services.length, 1, "an offline click re-reads connection state and never writes");
connected = true;
interactive.refresh();
const pendingButton = interactiveRoot.querySelectorAll("[data-mode]").find((button) => button.dataset.mode === "cool");
const pendingAction = pendingButton.click();
interactive.destroy();
deferredReject(new Error("late failure"));
await pendingAction;
assert.equal(interactiveRoot.replaceChildrenCalls, 1, "a late async failure cannot resurrect a destroyed view");

// Clock-only changes must update the visible clock without replacing the control
// tree. The clock is injected here only to make a minute transition deterministic.
const clockRoot = makeInteractiveRoot();
let displayNow = new Date(2026, 9, 12, 9, 5);
const clockHandle = api.mount(clockRoot, {
  getHass: () => liveHass,
  now: () => displayNow,
});
const clockWrites = clockRoot.writes;
const clockTime = clockRoot.querySelector("[data-frameos-clock-time]");
const clockPeriod = clockRoot.querySelector("[data-frameos-clock-period]");
const clockDate = clockRoot.querySelector("[data-frameos-clock-date]");
const initialClockText = clockTime?.textContent;
assert.ok(initialClockText, "the mounted Home exposes an updateable time node");
const clockControl = clockRoot.querySelectorAll("[data-mode]").find((button) => button.dataset.mode === "cool");
timers.filter((timer) => timer.delay === 1000 && !timer.cleared).at(-1).callback();
assert.equal(clockTime.assignments, 0, "an unchanged clock tick does not assign the existing time node");
assert.equal(clockPeriod.assignments, 0, "an unchanged clock tick does not assign the existing period node");
assert.equal(clockDate.assignments, 0, "an unchanged clock tick does not assign the existing date node");
displayNow = new Date(2026, 9, 12, 9, 6);
timers.filter((timer) => timer.delay === 1000 && !timer.cleared).at(-1).callback();
assert.equal(clockRoot.writes, clockWrites, "a minute tick does not replace the interactive Home tree");
assert.notEqual(clockRoot.querySelector("[data-frameos-clock-time]")?.textContent, initialClockText, "a minute tick updates the visible time in place");
assert.equal(clockTime.assignments, 1, "a minute transition updates the changed time node once");
assert.equal(clockPeriod.assignments, 0, "a minute transition does not assign an unchanged period");
assert.equal(clockDate.assignments, 0, "a minute transition does not assign an unchanged date");
assert.equal(clockRoot.querySelectorAll("[data-mode]").find((button) => button.dataset.mode === "cool"), clockControl, "a minute tick preserves the existing climate control node");
clockHandle.destroy();

const temperatureRoot = makeInteractiveRoot();
const temperatureServices = [];
const temperatureHass = {
  connected: true,
  config: { unit_system: { temperature: "°F" } },
  states: { "climate.entryway_entryway": { state: "heat", attributes: { hvac_modes: ["off", "heat"], min_temp: 60, max_temp: 80, target_temp_step: 1, temperature: 68, current_temperature: 68 } } },
  callService(domain, service, payload) { temperatureServices.push([domain, service, payload]); return Promise.resolve(); },
};
const temperatureHandle = api.mount(temperatureRoot, { getHass: () => temperatureHass });
temperatureRoot.querySelectorAll("[data-adjust]").find((button) => button.dataset.adjust === "temperature:1").click();
await temperatureRoot.querySelector("[data-apply]").click();
assert.equal(JSON.stringify(temperatureServices[0]), JSON.stringify(["climate", "set_temperature", { entity_id: "climate.entryway_entryway", temperature: 69 }]), "an explicit Apply click is required for a drafted temperature command");
temperatureHandle.destroy();

// No false camera availability, fabricated current reading, or silent overwrite
// after another controller changes the setpoint while this draft is open.
const guardedRoot = makeInteractiveRoot();
const guardedCalls = [];
const guardedHass = { connected:true, config:{unit_system:{temperature:'°F'}}, states:{
 'climate.entryway_entryway':{state:'heat',attributes:{hvac_modes:['off','heat'],min_temp:60,max_temp:80,target_temp_step:1,temperature:68,current_temperature:null}}
},callService(...args){guardedCalls.push(args);return Promise.resolve();}};
const guarded = api.mount(guardedRoot,{getHass:()=>guardedHass});
assert.match(guardedRoot.innerHTML,/0 cameras available/,'absent cameras are not available');
assert.match(guardedRoot.innerHTML,/fch-climate-temp">—</,'missing current temperature is not the target');
await guardedRoot.querySelector('[data-apply]').click();
assert.equal(guardedCalls.length,0,'Apply without edits must not resend state');
guardedRoot.querySelectorAll('[data-adjust]').find(b=>b.dataset.adjust==='temperature:1').click();
guardedHass.states['climate.entryway_entryway'].attributes.temperature=72;
await guardedRoot.querySelector('[data-apply]').click();
assert.equal(guardedCalls.length,0,'an external same-mode target change invalidates old drafts');
assert.match(guardedRoot.innerHTML,/72°F/);
guardedHass.states['climate.entryway_entryway'].state='unavailable';
guarded.refresh();
await guardedRoot.querySelectorAll('[data-mode]').find(b=>b.dataset.mode==='heat').click();
assert.equal(guardedCalls.length,0,'unavailable climate rejects mode writes even if old supported modes remain');
guarded.destroy();

const calendarRoot=makeInteractiveRoot();
const calendarHass={connected:true,states:{'calendar.family':{state:'on',attributes:{message:'Holiday',start_time:'2026-10-12 00:00:00',all_day:true}}},callService(){throw new Error('render cannot write');}};
const calendarHandle=api.mount(calendarRoot,{getHass:()=>calendarHass});
assert.match(calendarRoot.innerHTML,/All day/,'HA all_day boolean labels midnight events correctly');
assert.doesNotMatch(calendarRoot.innerHTML,/fch-weather-icon">☀/,'missing weather must not claim sunshine');
calendarHandle.destroy();

// Household weather uses the installed Met.no entity without choosing another home's station.
const weatherRoot=makeInteractiveRoot();
const weatherHass={connected:true,config:{unit_system:{temperature:'°F'}},states:{
 'weather.forecast_home':{state:'rainy',attributes:{temperature:17,temperature_unit:'°C'}},
 'weather.home':{state:'sunny',attributes:{temperature:80,temperature_unit:'°F'}},
 'weather.parents_weather':{state:'sunny',attributes:{temperature:99,temperature_unit:'°F'}}
},callService(){throw new Error('weather render cannot write');}};
const weatherHandle=api.mount(weatherRoot,{getHass:()=>weatherHass});
assert.match(weatherRoot.innerHTML,/fch-weather-temp">17°C</,'installed household forecast and its own unit win');
assert.match(weatherRoot.innerHTML,/fch-weather-state">rainy</);
weatherHass.states['weather.forecast_home'].state='unavailable';weatherHandle.refresh();
assert.match(weatherRoot.innerHTML,/fch-weather-temp">—°C</,'stale weather temperatures are hidden when unavailable');
delete weatherHass.states['weather.forecast_home'];weatherHandle.refresh();
assert.match(weatherRoot.innerHTML,/fch-weather-temp">80°F</,'generic weather.home remains compatible');
delete weatherHass.states['weather.home'];weatherHandle.refresh();
assert.doesNotMatch(weatherRoot.innerHTML,/99°F/,'never choose an unrelated household weather entity');
weatherHandle.destroy();
