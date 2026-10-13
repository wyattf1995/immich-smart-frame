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
assert.match(html, /MAX_NAVIGATION_GUARD_NODES/, "SPA navigation bounds shadow-DOM guard inspection conservatively");
assert.match(html, /MAX_NAVIGATION_GUARD_NODES = 4096/, "SPA guard inspection admits a normal HA dashboard before failing closed");
assert.match(html, /childWindow\.location\.search !== "\?kiosk"/, "SPA navigation accepts only the exact kiosk route query");
assert.match(html, /hass\.connection\?\.connected !== true/, "SPA navigation requires a positively connected HA session");

const root = {
  nodeType: 9,
  nodes: [],
  navigationBlocker: null,
  querySelector(selector) {
    if (selector === "home-assistant") return this.nodes.find((node) => node.localName === "home-assistant") || null;
    return this.navigationBlocker;
  },
  querySelectorAll() { return this.nodes; },
  createElement() {
    let textContent = "";
    return {
      id: "",
      textAssignmentCount: 0,
      get textContent() { return textContent; },
      set textContent(value) { this.textAssignmentCount += 1; textContent = value; },
    };
  },
};
const makeElement = (localName, options = {}) => {
  const element = {
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
  };
  if (element.shadowRoot) element.shadowRoot.host = element;
  return element;
};
const makeShadow = (children = [], textContent = "") => {
  const styles = [];
  return {
    nodeType: 11,
    textContent,
    navigationBlocker: null,
    querySelector(selector) { return this.navigationBlocker || styles.find((style) => style.id === selector.slice(1)) || null; },
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
const clockStrong = makeElement("strong", { text: "" });
const markdownElement = makeElement("ha-markdown-element", { text: "", children: [clockStrong] });
const markdown = makeElement("ha-markdown", { shadowRoot: makeShadow([markdownElement]) });
const markdownCard = makeElement("hui-markdown-card", { shadowRoot: makeShadow([markdown]) });
const statusStrong = makeElement("strong", { text: "Entryway thermostat:" });
const statusElement = makeElement("ha-markdown-element", { text: "Entryway thermostat: off", children: [statusStrong] });
const statusMarkdown = makeElement("ha-markdown", { shadowRoot: makeShadow([statusElement]) });
const statusCard = makeElement("hui-markdown-card", { shadowRoot: makeShadow([statusMarkdown]) });
const sectionsView = makeElement("hui-sections-view", { shadowRoot: makeShadow([markdownCard, statusCard]) });
const headingShadow = { nodeType: 11, querySelectorAll() { return [...headingCards, sectionsView]; } };
root.nodes = [makeElement("home-assistant", { shadowRoot: headingShadow })];
root.nodes[0].hass = {
  states: {},
  connected: true,
  connection: { connected: true },
  callService() {},
};

const windowListeners = new Map();
const childListeners = new Map();
const panelListeners = new Map();
const observers = [];
const timers = [];
const loading = { hidden: false, textContent: "", classList: { add() {}, remove() {} } };
const status = { textContent: "" };
const homeChrome = { hidden: true };
const childHistoryCalls = [];
const childEvents = [];
const childWindow = {
  location: { origin: "https://frame.example", pathname: "/frameos-touchscreen/home", search: "" },
  history: {
    state: null,
    pushState(state, _title, target) {
      childHistoryCalls.push({ state, target });
      this.state = state;
      const parsed = new URL(target, "https://frame.example");
      childWindow.location.pathname = parsed.pathname;
      childWindow.location.search = parsed.search;
    },
  },
  Event: class {
    constructor(type, options = {}) { this.type = type; this.bubbles = options.bubbles; this.composed = options.composed; }
  },
  focus() {},
  dispatchEvent(event) {
    childEvents.push(event);
    childListeners.get(event.type)?.(event);
    return true;
  },
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
  location: { origin: "https://frame.example", hash: "", pathname: "/local/frameos-touchscreen-panel.html", search: "" },
  history: { replaceState(_state, _title, value) { context.location.hash = value.slice(value.indexOf("#")); } },
  MutationObserver: class {
    constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this); }
    observe(target) { this.target = target; }
    disconnect() { this.disconnected = true; }
  },
  setTimeout(callback, delay) { const timer = { callback, delay, id: timers.length + 1, cleared: false }; timers.push(timer); return timer.id; },
  clearTimeout(id) { const timer = timers.find((candidate) => candidate.id === id); if (timer) timer.cleared = true; },
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
assert.equal(markdown.shadowRoot.styles.filter((style) => style.id === "frameos-home-markdown").length, 1, "empty markdown gets a normal placeholder style while Home Assistant renders asynchronously");
assert.doesNotMatch(markdown.shadowRoot.styles.find((style) => style.id === "frameos-home-markdown").textContent, /display:block/, "an empty markdown card is never prematurely styled as a clock");
assert.doesNotMatch(statusMarkdown.shadowRoot.styles.find((style) => style.id === "frameos-home-markdown").textContent, /display:block/, "ordinary status strong text never receives clock typography");
const markdownObserver = observers.find((observer) => observer.target === markdown.shadowRoot);
assert.ok(markdownObserver, "the ha-markdown shadow root is observed for asynchronous content");
clockStrong.textContent = "07:16";
markdownElement.textContent = "07:16 Sunday";
markdownObserver.callback([{ type: "childList", addedNodes: [clockStrong] }]);
const clockStyle = markdown.shadowRoot.styles.find((style) => style.id === "frameos-home-markdown");
assert.match(clockStyle.textContent, /display:block;[^}]*6\.25rem/, "a child-list clock insertion refreshes the existing deep shadow style");
const clockStyleAssignments = clockStyle.textAssignmentCount;
markdownObserver.callback([{ type: "childList", addedNodes: [clockStrong] }]);
assert.equal(clockStyle.textAssignmentCount, clockStyleAssignments, "identical child-list updates do not rewrite the existing clock style");
assert.equal(markdown.shadowRoot.styles.filter((style) => style.id === "frameos-home-markdown").length, 1, "stable markdown mutations never append duplicate deep styles");
headingCards.forEach((card) => { card.shadowRoot.textContent = card.expectedShadowText; });
const characterDataObserver = observers.at(-1);
headingCards.forEach((card) => characterDataObserver.callback([{ type: "characterData", target: { parentElement: card } }]));
assert.equal(loading.hidden, true, "delayed HA heading shadow text makes Home ready");
headingCards.forEach((card) => {
  assert.ok(card.style.values.some(([name, value]) => name === "--primary-text-color" && value === "#f1ede4"), "Home heading host receives the requested warm-ivory high-contrast text variable");
  assert.equal(card.shadowRoot.styles.filter((style) => style.id === "frameos-heading-contrast").length, 1, "Home heading shadow receives exactly one contrast style");
});
const homeStyleObserver = observers.find((observer) => observer.target === root && !observer.disconnected);
assert.ok(homeStyleObserver, "Home keeps a scoped style observer after readiness completes");
const lateClockStrong = makeElement("strong", { text: "08:00" });
const lateMarkdownElement = makeElement("ha-markdown-element", { text: "08:00 Monday", children: [lateClockStrong] });
const lateMarkdown = makeElement("ha-markdown", { shadowRoot: makeShadow([lateMarkdownElement]) });
const lateMarkdownCard = makeElement("hui-markdown-card", { shadowRoot: makeShadow([lateMarkdown]) });
const lateSectionsView = makeElement("hui-sections-view", { shadowRoot: makeShadow([lateMarkdownCard]) });
const lateHomeAssistant = makeElement("home-assistant");
homeStyleObserver.callback([{ type: "childList", addedNodes: [lateHomeAssistant] }]);
const homeLateShadowProbe = timers.filter((timer) => timer.delay === 50 && !timer.cleared).at(-1);
assert.ok(homeLateShadowProbe, "a late custom Home component without a shadow root arms a bounded style probe");
lateHomeAssistant.shadowRoot = makeShadow([lateSectionsView]);
lateHomeAssistant.shadowRoot.host = lateHomeAssistant;
homeLateShadowProbe.callback();
assert.equal(lateSectionsView.shadowRoot.styles.filter((style) => style.id === "frameos-home-redesign").length, 1, "a late ancestor shadow root traverses to the nested sections palette");
assert.equal(lateMarkdownCard.shadowRoot.styles.filter((style) => style.id === "frameos-home-card").length, 1, "a late ancestor shadow root traverses to the nested card treatment");
assert.equal(lateMarkdown.shadowRoot.styles.filter((style) => style.id === "frameos-home-markdown").length, 1, "a late ancestor shadow root traverses to the nested markdown treatment");
const neverShadowHost = makeElement("hui-markdown-card");
homeStyleObserver.callback([{ type: "childList", addedNodes: [neverShadowHost] }]);
const pendingHomeProbe = timers.filter((timer) => timer.delay === 50 && !timer.cleared).at(-1);
const firstSpaListener = childListeners.get("location-changed");
panelListeners.get("load")();
assert.notEqual(childListeners.get("location-changed"), firstSpaListener, "a full iframe load reattaches navigation listeners even when WindowProxy identity is stable");

childWindow.location.pathname = "/auth/authorize";
panelListeners.get("load")();
assert.equal(homeChrome.hidden, true, "an authentication load removes the Home-only header");
assert.ok(!panel.classList.classes.has("home-layout"), "an authentication load restores full-frame iframe geometry");
assert.equal(pendingHomeProbe.cleared, true, "authentication cleanup cancels a pending Home shadow probe");
childListeners.get("location-changed")();
childListeners.get("popstate")();
assert.equal(homeChrome.hidden, true, "authentication SPA navigation never restores the Home header");
assert.ok(!panel.classList.classes.has("home-layout"), "authentication SPA navigation never restores Home iframe geometry");
assert.ok(homeStyleObserver.disconnected, "authentication SPA navigation keeps Home shadow observers disposed");
childWindow.location.pathname = "/frameos-touchscreen/home";
childListeners.get("location-changed")();
assert.equal(homeChrome.hidden, false, "a valid Home route resumes the Home header after sign-in");
assert.ok(panel.classList.classes.has("home-layout"), "a valid Home route resumes reserved Home iframe geometry after sign-in");

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

root.nodes = [makeElement("home-assistant", { shadowRoot: headingShadow })];
root.nodes[0].hass = { states: {}, connected: true, connection: { connected: true }, callService() {} };
root.navigationBlocker = null;
childWindow.location.origin = "https://frame.example";
childWindow.location.pathname = "/frameos-touchscreen/home";
childWindow.location.search = "?kiosk";
panel.src = "";
const historyBeforeFastPath = childHistoryCalls.length;
context.location.hash = "#calendar";
windowListeners.get("hashchange")();
assert.equal(panel.src, "", "an authenticated same-origin HA shell reuses the iframe rather than assigning src");
assert.equal(childHistoryCalls.length, historyBeforeFastPath + 1, "SPA navigation writes one HA-style child history entry");
assert.equal(childHistoryCalls.at(-1).state.from, "/frameos-touchscreen/home?kiosk", "SPA history preserves the previous route for Back");
assert.equal(childHistoryCalls.at(-1).target, "/frameos-touchscreen/calendar?kiosk", "SPA navigation can target only the fixed Calendar route");
assert.equal(childEvents.at(-1).type, "location-changed", "SPA navigation emits Home Assistant's route event");
assert.equal(childEvents.at(-1).detail.replace, false, "SPA event carries the Home Assistant replace detail");
assert.equal(childEvents.at(-1).bubbles, true, "SPA route event matches Home Assistant bubbling semantics");
assert.equal(childEvents.at(-1).composed, true, "SPA route event matches Home Assistant composed semantics");

root.navigationBlocker = { localName: "ha-dialog" };
const historyBeforeDialog = childHistoryCalls.length;
context.location.hash = "#home";
windowListeners.get("hashchange")();
assert.equal(panel.src, "/frameos-touchscreen/home?kiosk", "an active HA dialog forces the conservative iframe reload");
assert.equal(childHistoryCalls.length, historyBeforeDialog, "dialog fallback does not mutate HA history");
root.navigationBlocker = null;

childWindow.location.origin = "https://other.example";
panel.src = "";
context.location.hash = "#cameras";
windowListeners.get("hashchange")();
assert.equal(panel.src, "/frameos-touchscreen/cameras?kiosk", "cross-origin uncertainty keeps the full iframe reload fallback");
childWindow.location.origin = "https://frame.example";

const nestedGuardHost = makeElement("ha-navigation-guard-host", { shadowRoot: makeShadow() });
nestedGuardHost.shadowRoot.navigationBlocker = { localName: "ha-dialog" };
root.nodes = [makeElement("home-assistant", { shadowRoot: headingShadow }), nestedGuardHost];
root.nodes[0].hass = { states: {}, connected: true, connection: { connected: true }, callService() {} };
childWindow.location.pathname = "/frameos-touchscreen/home";
childWindow.location.search = "?kiosk";
panel.src = "";
const historyBeforeNestedDialog = childHistoryCalls.length;
context.location.hash = "#calendar";
windowListeners.get("hashchange")();
assert.equal(panel.src, "/frameos-touchscreen/calendar?kiosk", "a dialog in a reachable HA shadow root forces a full reload");
assert.equal(childHistoryCalls.length, historyBeforeNestedDialog, "shadow dialog fallback does not mutate HA history");

root.nodes = [makeElement("home-assistant", { shadowRoot: headingShadow })];
root.nodes[0].hass = { states: {}, connected: true, connection: { connected: true }, callService() {} };
childWindow.location.pathname = "/frameos-touchscreen/home";
childWindow.location.search = "?kiosk&edit=1";
panel.src = "";
context.location.hash = "#cameras";
windowListeners.get("hashchange")();
assert.equal(panel.src, "/frameos-touchscreen/cameras?kiosk", "unknown or editing route queries force a full reload");

root.nodes[0].hass = { states: {}, connected: true };
childWindow.location.pathname = "/frameos-touchscreen/home";
childWindow.location.search = "?kiosk";
panel.src = "";
context.location.hash = "#calendar";
windowListeners.get("hashchange")();
assert.equal(panel.src, "/frameos-touchscreen/calendar?kiosk", "an uncertain HA connection forces a full reload");

root.nodes = [makeElement("home-assistant", { shadowRoot: headingShadow }), ...Array.from({ length: 4097 }, () => makeElement("ha-dashboard-node"))];
root.nodes[0].hass = { states: {}, connected: true, connection: { connected: true }, callService() {} };
childWindow.location.pathname = "/frameos-touchscreen/home";
childWindow.location.search = "?kiosk";
panel.src = "";
const historyBeforeGuardBudget = childHistoryCalls.length;
context.location.hash = "#cameras";
windowListeners.get("hashchange")();
assert.equal(panel.src, "/frameos-touchscreen/cameras?kiosk", "a guard traversal beyond the bounded dashboard budget fails closed");
assert.equal(childHistoryCalls.length, historyBeforeGuardBudget, "budget exhaustion does not mutate HA history");

console.log("FrameOS touchscreen panel shadow readiness and guarded SPA navigation tests passed");
