import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const html = fs.readFileSync(new URL("../../examples/frameos/frameos-touchscreen-panel.html", import.meta.url), "utf8");
const script = html.match(/<script>\s*([\s\S]*?)\s*<\/script>/)?.[1];
assert.ok(script, "panel must contain executable wrapper script");
assert.match(html, /id="home-chrome"/, "Home has a wrapper-reserved header instead of an overlay on thermostat controls");
assert.match(html, /#panel\.home-layout/, "Home reserves iframe space below its wrapper header");
assert.match(html, /const activateHomeStyles/, "Home styling is activated only for the Home route");
assert.match(html, /const disposeHomeStyles/, "Home styling has explicit SPA-route cleanup");
assert.match(html, /#frameos-home-redesign/, "the verified sections-view shadow receives a scoped Home palette");
assert.match(html, /hui-thermostat-card/, "the stock thermostat card receives only scoped visual treatment");
assert.match(html, /#f1ede4/, "Home uses the requested warm-ivory foreground");
assert.match(html, /#d8c390/, "Home uses the requested muted-gold accent");
assert.match(html, /#182a2c/, "Home uses the requested deep-green card surface");
assert.doesNotMatch(html, /pointer-events\s*:\s*none[^}]*hui-thermostat-card|hui-thermostat-card[^}]*pointer-events\s*:\s*none/, "thermostat controls remain interactive");

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
  querySelector(selector) { return (options.children || []).find((child) => child.localName === selector) || null; },
});
const makeShadow = (children = [], textContent = "") => {
  const styles = [];
  return {
    nodeType: 11,
    textContent,
    querySelector(selector) { return styles.find((style) => style.id === selector.slice(1)) || null; },
    querySelectorAll() { return [...children]; },
    append(style) { styles.push(style); },
    styles,
  };
};
const headingCards = ["Today", "Up next", "Home status", "Comfort"].map((heading) => {
  const styles = [];
  return makeElement("hui-heading-card", {
    shadowRoot: makeShadow([], ""),
    text: "",
    heading: "",
    expectedShadowText: heading,
  });
});
const clockStrong = makeElement("strong", { text: "07:16" });
const markdownElement = makeElement("ha-markdown-element", { text: "07:16 Sunday", children: [clockStrong] });
const markdown = makeElement("ha-markdown", { shadowRoot: makeShadow([markdownElement]) });
const markdownCard = makeElement("hui-markdown-card", { shadowRoot: makeShadow([markdown]) });
const sectionsView = makeElement("hui-sections-view", { shadowRoot: makeShadow([markdownCard]) });
const headingShadow = { nodeType: 11, querySelectorAll() { return [...headingCards, sectionsView]; } };
root.nodes = [makeElement("home-assistant", { shadowRoot: headingShadow })];

const windowListeners = new Map();
const childListeners = new Map();
const panelListeners = new Map();
const observers = [];
const timers = [];
const loading = { hidden: false, textContent: "", classList: { add() {}, remove() {} } };
const status = { textContent: "" };
const homeChrome = { hidden: true };
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
  classList: { classes: new Set(), toggle(name, enabled) { if (enabled) this.classes.add(name); else this.classes.delete(name); } },
};
const context = {
  window: null,
  document: {
    getElementById(id) { return ({ panel, loading, status, "home-chrome": homeChrome })[id]; },
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
assert.equal(homeChrome.hidden, false, "Home header is active only while the Home route is loading");
assert.ok(panel.classList.classes.has("home-layout"), "Home header reserves iframe space instead of covering controls");
assert.equal(sectionsView.shadowRoot.styles.filter((style) => style.id === "frameos-home-redesign").length, 1, "Home palette traverses the nested sections-view shadow root");
assert.equal(markdown.shadowRoot.styles.filter((style) => style.id === "frameos-home-markdown").length, 1, "clock styling crosses the ha-markdown shadow boundary");
assert.match(markdown.shadowRoot.styles.find((style) => style.id === "frameos-home-markdown").textContent, /display:block;[^}]*6\.25rem/, "only the clock stylesheet carries the large display treatment");
headingCards.forEach((card) => { card.shadowRoot.textContent = card.expectedShadowText; });
const characterDataObserver = observers.at(-1);
headingCards.forEach((card) => characterDataObserver.callback([{ type: "characterData", target: { parentElement: card } }]));
assert.equal(loading.hidden, true, "delayed HA heading shadow text makes Home ready");
headingCards.forEach((card) => {
  assert.ok(card.style.values.some(([name, value]) => name === "--primary-text-color" && value === "#f1ede4"), "Home heading host receives the requested warm-ivory high-contrast text variable");
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
assert.equal(homeChrome.hidden, true, "Camera navigation removes the Home-only header");
assert.ok(!panel.classList.classes.has("home-layout"), "Camera navigation removes Home iframe spacing");

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
