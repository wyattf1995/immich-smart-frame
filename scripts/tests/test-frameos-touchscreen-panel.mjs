import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../../examples/frameos/frameos-touchscreen-panel.html", import.meta.url), "utf8");
const script = source.match(/<script>\s*([\s\S]*?)\s*<\/script>/)?.[1];
assert.ok(script, "panel must contain executable wrapper script");

const listeners = new Map();
const panelListeners = new Map();
const loading = { hidden: false, textContent: "", classList: { add() {}, remove() {} } };
const status = { textContent: "" };
let observerCount = 0;
const root = {
  querySelector(selector) {
    if (selector === "ha-full-calendar" && root.calendar) return { localName: "ha-full-calendar" };
    return selector === "hui-picture-entity-card" && root.camera ? { localName: "hui-picture-entity-card" } : null;
  },
  querySelectorAll() {
    return root.headings.map((text) => ({ heading: text, textContent: text, getAttribute() { return null; } }));
  },
  headings: ["Today", "Up next", "Home status", "Comfort"],
  calendar: false,
  camera: false,
};
const panel = {
  src: "",
  contentDocument: root,
  contentWindow: { focus() {}, location: { pathname: "/frameos-touchscreen/home" } },
  addEventListener(type, callback) { panelListeners.set(type, callback); },
  focus() {},
};
const context = {
  window: null,
  document: { getElementById(id) { return ({ panel, loading, status })[id]; } },
  location: { hash: "", pathname: "/local/frameos-touchscreen-panel.html", search: "" },
  history: { replaceState(_state, _title, location) { context.location.hash = location.slice(location.indexOf("#")); } },
  MutationObserver: class {
    constructor(callback) { this.callback = callback; this.disconnected = false; observerCount += 1; }
    observe() {}
    disconnect() { this.disconnected = true; }
  },
  setTimeout() { return 1; },
  clearTimeout() {},
};
context.window = context;
context.addEventListener = (type, callback) => listeners.set(type, callback);
vm.runInNewContext(script, context, { filename: "frameos-touchscreen-panel.inline.js" });

assert.equal(panel.src, "/frameos-touchscreen/home?kiosk");
assert.equal(context.FrameOSTouchscreenPanel.normalizeView("nope"), "home");
assert.equal(context.FrameOSTouchscreenPanel.routeFor("calendar"), "/frameos-touchscreen/calendar?kiosk");

panelListeners.get("load")();
assert.equal(loading.hidden, true, "all four Home headings make the wrapper ready");

root.headings = [];
context.location.hash = "#cameras";
listeners.get("hashchange")();
assert.equal(panel.src, "/frameos-touchscreen/cameras?kiosk");
panelListeners.get("load")();
const cameraObserverCount = observerCount;
context.location.hash = "#calendar";
listeners.get("hashchange")();
assert.equal(panel.src, "/frameos-touchscreen/calendar?kiosk");
panelListeners.get("load")();
assert.ok(observerCount > cameraObserverCount, "route switch creates a fresh bounded observer");

root.calendar = false;
root.camera = true;
panel.contentWindow.location.pathname = "/frameos-touchscreen/camera-1";
panelListeners.get("load")();
assert.equal(loading.hidden, true, "a camera subview becomes ready only with its single live card");
assert.equal(status.textContent, "Camera ready");

const cameras = context.FrameOSTouchscreenPanel.createCameraMountController();
assert.equal(cameras.select("camera.entryway"), "camera.entryway");
assert.equal(cameras.mounted(), "camera.entryway");
cameras.select("camera.backyard");
assert.equal(cameras.mounted(), "camera.backyard", "new selection replaces the single mount");
cameras.dispose();
assert.equal(cameras.mounted(), null, "leaving a camera disposes the mount");

console.log("FrameOS touchscreen panel route/readiness/camera-disposal tests passed");
