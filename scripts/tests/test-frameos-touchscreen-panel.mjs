import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const html = fs.readFileSync(new URL("../../examples/frameos/frameos-touchscreen-panel.html", import.meta.url), "utf8");
const script = html.match(/<script>\s*([\s\S]*?)\s*<\/script>/)?.[1];
assert.ok(script, "panel must contain executable wrapper script");

const root = {
  nodeType: 9,
  nodes: [],
  querySelectorAll() { return this.nodes; },
  createElement() { return { id: "", textContent: "" }; },
};
const makeElement = (localName, options = {}) => ({
  nodeType: 1,
  localName,
  heading: options.heading || "",
  textContent: options.text || options.heading || "",
  expectedShadowText: options.expectedShadowText || "",
  shadowRoot: options.shadowRoot || null,
  style: { values: [], setProperty(name, value, priority) { this.values.push([name, value, priority]); } },
  ownerDocument: root,
  isConnected: true,
  getAttribute() { return null; },
  querySelectorAll() { return options.children || []; },
});
const headingCards = ["Today", "Up next", "Home status", "Comfort"].map((heading) => {
  const styles = [];
  return makeElement("hui-heading-card", {
    shadowRoot: {
      nodeType: 11,
      textContent: "",
      querySelector(selector) { return styles.find((style) => style.id === selector.slice(1)) || null; },
      append(style) { styles.push(style); },
      querySelectorAll() { return []; },
      styles,
    },
    text: "",
    heading: "",
    expectedShadowText: heading,
  });
});
const headingShadow = { nodeType: 11, querySelectorAll() { return headingCards; } };
root.nodes = [makeElement("home-assistant", { shadowRoot: headingShadow })];

const windowListeners = new Map();
const childListeners = new Map();
const panelListeners = new Map();
const observers = [];
const timers = [];
const loading = { hidden: false, textContent: "", classList: { add() {}, remove() {} } };
const status = { textContent: "" };
const childWindow = {
  location: { pathname: "/frameos-touchscreen/home" },
  focus() {},
  addEventListener(type, callback) { childListeners.set(type, callback); },
  removeEventListener(type, callback) { if (childListeners.get(type) === callback) childListeners.delete(type); },
};
const panel = {
  src: "",
  contentDocument: root,
  contentWindow: childWindow,
  addEventListener(type, callback) { panelListeners.set(type, callback); },
  focus() {},
};
const context = {
  window: null,
  document: {
    getElementById(id) { return ({ panel, loading, status })[id]; },
    createElement() { return { id: "", textContent: "" }; },
  },
  location: { hash: "", pathname: "/local/frameos-touchscreen-panel.html", search: "" },
  history: { replaceState(_state, _title, value) { context.location.hash = value.slice(value.indexOf("#")); } },
  MutationObserver: class {
    constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this); }
    observe() {}
    disconnect() { this.disconnected = true; }
  },
  setTimeout(callback, delay) { const timer = { callback, delay, id: timers.length + 1 }; timers.push(timer); return timer.id; },
  clearTimeout() {},
};
context.window = context;
context.addEventListener = (type, callback) => windowListeners.set(type, callback);
vm.runInNewContext(script, context, { filename: "frameos-touchscreen-panel.inline.js" });

assert.equal(panel.src, "/frameos-touchscreen/home?kiosk");
assert.equal(context.FrameOSTouchscreenPanel.normalizeView("nope"), "home");
assert.equal(context.FrameOSTouchscreenPanel.routeFor("calendar"), "/frameos-touchscreen/calendar?kiosk");
panelListeners.get("load")();
assert.equal(loading.hidden, false, "empty HA heading hosts are not ready before delayed shadow text arrives");
headingCards.forEach((card) => { card.shadowRoot.textContent = card.expectedShadowText; });
const characterDataObserver = observers.at(-1);
headingCards.forEach((card) => characterDataObserver.callback([{ type: "characterData", target: { parentElement: card } }]));
assert.equal(loading.hidden, true, "delayed HA heading shadow text makes Home ready");
headingCards.forEach((card) => {
  assert.ok(card.style.values.some(([name, value]) => name === "--primary-text-color" && value === "#fff"), "Home heading host receives the established high-contrast text variable");
  assert.equal(card.shadowRoot.styles.filter((style) => style.id === "frameos-heading-contrast").length, 1, "Home heading shadow receives exactly one contrast style");
});
const firstSpaListener = childListeners.get("location-changed");
panelListeners.get("load")();
assert.notEqual(childListeners.get("location-changed"), firstSpaListener, "a full iframe load reattaches navigation listeners even when WindowProxy identity is stable");

const oldObservers = observers.slice();
const cameraCard = makeElement("hui-picture-entity-card");
const cameraShadow = { nodeType: 11, querySelectorAll() { return [cameraCard]; } };
root.nodes = [makeElement("home-assistant", { shadowRoot: cameraShadow })];
childWindow.location.pathname = "/frameos-touchscreen/camera-1";
childListeners.get("location-changed")();
assert.equal(loading.hidden, true, "SPA camera navigation is ready only after the one camera card appears");
assert.equal(status.textContent, "Camera ready");
assert.ok(oldObservers.every((observer) => observer.disconnected), "leaving Home disconnects its route observers");

root.nodes = [makeElement("home-assistant", { shadowRoot: { nodeType: 11, querySelectorAll() { return [makeElement("hui-heading-card", { heading: "Camera chooser" })]; } } })];
childWindow.location.pathname = "/frameos-touchscreen/cameras";
childListeners.get("location-changed")();
assert.equal(loading.hidden, true, "returning to chooser disposes the camera route and needs no live card");
assert.equal(status.textContent, "Cameras ready");

const timeoutObserverStart = observers.length;
root.nodes = [makeElement("ha-never-ready")];
childWindow.location.pathname = "/frameos-touchscreen/camera-2";
childListeners.get("location-changed")();
const routeTimeout = timers.filter((timer) => timer.delay === 12_000).at(-1);
assert.ok(routeTimeout, "a route deadline is armed before waiting for shadow roots");
routeTimeout.callback();
assert.equal(status.textContent, "Camera still connecting");
assert.ok(observers.slice(timeoutObserverStart).every((observer) => observer.disconnected), "deadline disconnects pending shadow observers instead of polling forever");

root.nodes = [makeElement("home-assistant", { shadowRoot: cameraShadow })];
childWindow.location.pathname = "/frameos-touchscreen/camera-1";
childListeners.get("location-changed")();
assert.equal(status.textContent, "Camera ready", "SPA watcher can restart readiness after a bounded timeout");

root.nodes = [];
childWindow.location.pathname = "/frameos-touchscreen/camera-3";
childListeners.get("location-changed")();
const lateHost = makeElement("ha-late-camera-host");
const lateHostObserver = observers.at(-1);
const shadowProbeCountBefore = timers.filter((timer) => timer.delay === 50).length;
lateHostObserver.callback([{ type: "childList", addedNodes: [lateHost] }]);
const lateShadowProbe = timers.filter((timer) => timer.delay === 50).at(-1);
assert.ok(timers.filter((timer) => timer.delay === 50).length > shadowProbeCountBefore, "a custom host added after an initially empty route arms a shadow probe");
lateHost.shadowRoot = { nodeType: 11, querySelectorAll() { return [cameraCard]; } };
lateShadowProbe.callback();
assert.equal(status.textContent, "Camera ready", "a late-attached camera shadow root is inspected by its bounded probe");

childWindow.location.pathname = "/auth/authorize";
loading.hidden = false;
panelListeners.get("load")();
assert.equal(loading.hidden, true, "an HA sign-in load removes the opaque loading overlay immediately");
assert.equal(status.textContent, "Sign-in required");

console.log("FrameOS touchscreen panel shadow readiness and camera lifecycle tests passed");
