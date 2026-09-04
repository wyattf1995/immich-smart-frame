"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function loadScript({ connectNative, historyValues = [], frames, push = false } = {}) {
  const events = [];
  const nativeMessages = [];
  const microtasks = [];
  const timers = [];
  const observers = [];
  const listeners = [];
  const classes = new Set();
  const next = { clicks: 0, click() { this.clicks += 1; } };
  const previous = { clicks: 0, click() { this.clicks += 1; } };
  class HTMLElement {}
  class HTMLImageElement extends HTMLElement {}
  Object.setPrototypeOf(next, HTMLElement.prototype);
  Object.setPrototypeOf(previous, HTMLElement.prototype);
  const history = historyValues.map((value) => ({ value }));
  const makeElement = (visible = true) => Object.assign(new HTMLElement(), {
    visible,
    parentElement: null,
    getBoundingClientRect() { return { width: this.visible ? 100 : 0, height: this.visible ? 100 : 0 }; },
  });
  const kioskContainer = makeElement();
  kioskContainer.classList = { contains: (name) => push && name === "transition-push" };
  const kiosk = makeElement();
  kiosk.parentElement = kioskContainer;
  const frameList = (frames || []).map(({ images, visible = true }) => {
    const frame = makeElement(visible);
    frame.parentElement = kiosk;
    frame.images = images.map((source) => Object.assign(new HTMLImageElement(), {
      parentElement: frame,
      complete: true,
      naturalWidth: 100,
      naturalHeight: 100,
      currentSrc: source,
      src: source,
      getBoundingClientRect() { return { width: 100, height: 100 }; },
    }));
    frame.querySelectorAll = (selector) => selector === "img[alt='Main image']" ? frame.images : [];
    return frame;
  });
  kioskContainer.querySelector = (selector) => selector === ":scope > #kiosk" ? kiosk : null;
  kiosk.querySelectorAll = (selector) => selector === ":scope > .frame" ? frameList : [];
  const body = {
    classList: { contains: (name) => classes.has(name) },
    dispatchEvent(event) {
      events.push(event);
      if (event.type === "keydown" && event.code === "KeyP") {
        if (event.shiftKey) classes.delete("polling-paused"); else classes.add("polling-paused");
      }
    },
  };
  const port = { onMessage: { addListener(listener) { port.listener = listener; } } };
  const context = {
    ASSET_ID: undefined,
    HTMLElement,
    HTMLImageElement,
    KeyboardEvent: class KeyboardEvent { constructor(type, init) { this.type = type; Object.assign(this, init); } },
    MutationObserver: class MutationObserver {
      constructor(callback) { this.callback = callback; observers.push(this); }
      observe(target, options) { this.target = target; this.options = options; }
    },
    browser: { runtime: { connectNative: connectNative || (() => port), sendNativeMessage(_name, message) { nativeMessages.push(message); return Promise.resolve({ accepted: true }); } } },
    document: {
      body,
      documentElement: {},
      addEventListener(...args) { listeners.push(args); },
      querySelector(selector) {
        if (selector === "#kiosk-history input[name='history']") return history[0] || null;
        if (selector === "#kiosk-container") return kioskContainer;
        if (selector === ".navigation--next-asset") return next;
        if (selector === ".navigation--prev-asset") return previous;
        return null;
      },
      querySelectorAll(selector) {
        if (selector === "#kiosk-history input[name='history']") return history;
        return [];
      },
    },
    getComputedStyle(element) { return { display: "block", visibility: "visible", opacity: element.visible === false ? "0" : "1" }; },
    queueMicrotask(callback) { microtasks.push(callback); },
    setTimeout(callback, delay) { timers.push({ callback, delay }); return timers.length; },
    clearTimeout() {},
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../../main/assets/frame-photo-bridge/photos.js"), "utf8"), context);
  return {
    body, events, next, previous, port, timers, observers, listeners, classes, nativeMessages,
    runMicrotasks() { while (microtasks.length) microtasks.shift()(); },
    setFrameVisible(index, visible) { frameList[index].visible = visible; },
    fireEvent(type) { listeners.filter(([eventType]) => eventType === type).forEach(([, listener]) => listener({ type })); },
  };
}

test("pause command targets body with the pinned KeyP contract", () => {
  const runtime = loadScript();
  runtime.port.listener({ type: "pause", paused: true });
  assert.equal(runtime.events.length, 1);
  assert.equal(runtime.events[0].type, "keydown");
  assert.equal(runtime.events[0].code, "KeyP");
  assert.equal(runtime.events[0].key, "p");
  assert.equal(runtime.events[0].shiftKey, false);
  assert.equal(runtime.events[0].bubbles, true);
  assert.equal(runtime.body.classList.contains("polling-paused"), true);
});

test("step clicks pinned navigation and restores desired paused state after swap", () => {
  const runtime = loadScript();
  runtime.port.listener({ type: "pause", paused: true });
  runtime.port.listener({ type: "step", forward: true });
  assert.equal(runtime.next.clicks, 1);
  runtime.classes.delete("polling-paused");
  runtime.observers.find((observer) => observer.target === runtime.body).callback();
  assert.equal(runtime.events.filter((event) => event.code === "KeyP").length, 2);
});

test("step falls back to the exact body ArrowLeft HTMX contract", () => {
  const runtime = loadScript();
  Object.setPrototypeOf(runtime.previous, Object.prototype);
  runtime.port.listener({ type: "step", forward: false });
  assert.equal(runtime.events[0].type, "keyup");
  assert.equal(runtime.events[0].key, "ArrowLeft");
  assert.equal(runtime.events[0].bubbles, true);
});

test("failed native port connection backs off without exhausting and leaves capture observers installed", () => {
  let attempts = 0;
  const runtime = loadScript({ connectNative() { attempts += 1; throw new Error("native unavailable"); } });
  const delays = [];
  for (let index = 0; index < 5; index += 1) {
    const timer = runtime.timers.shift();
    delays.push(timer.delay);
    timer.callback();
  }
  assert.equal(attempts, 6);
  assert.deepEqual(delays, [1000, 2000, 4000, 8000, 16000]);
  assert.equal(runtime.listeners.length, 1);
  assert.equal(runtime.observers.length, 2);
});

test("capture uses the last current frame for normal forward and backward transitions", () => {
  const runtime = loadScript({
    historyValues: ["11111111-1111-4111-8111-111111111111:old", "*22222222-2222-4222-8222-222222222222:current"],
    frames: [
      { images: ["data:image/jpeg;base64,old"] },
      { images: ["data:image/jpeg;base64,current"] },
    ],
  });
  runtime.runMicrotasks();
  assert.equal(runtime.nativeMessages.length, 1);
  assert.equal(runtime.nativeMessages[0].assetId, "22222222-2222-4222-8222-222222222222");
});

test("capture uses the first current frame for push transitions", () => {
  const pushed = loadScript({
    historyValues: ["11111111-1111-4111-8111-111111111111:old", "*22222222-2222-4222-8222-222222222222:current", "33333333-3333-4333-8333-333333333333:old"],
    push: true,
    frames: [
      { images: ["data:image/jpeg;base64,current"] },
      { images: ["data:image/jpeg;base64,old"] },
    ],
  });
  pushed.runMicrotasks();
  assert.equal(pushed.nativeMessages[0].assetId, "22222222-2222-4222-8222-222222222222");
});

test("capture rejects missing or duplicate active history and split views in the current frame", () => {
  for (const historyValues of [
    ["11111111-1111-4111-8111-111111111111:old"],
    ["*11111111-1111-4111-8111-111111111111:one", "*22222222-2222-4222-8222-222222222222:two"],
  ]) {
    const rejected = loadScript({ historyValues, frames: [{ images: ["data:image/jpeg;base64,abc"] }] });
    rejected.runMicrotasks();
    assert.equal(rejected.nativeMessages.length, 0);
  }
  const split = loadScript({
    historyValues: ["*22222222-2222-4222-8222-222222222222:current"],
    frames: [{ images: ["data:image/jpeg;base64,left", "data:image/jpeg;base64,right"] }],
  });
  split.runMicrotasks();
  assert.equal(split.nativeMessages.length, 0);
});

test("capture after a paused step uses the active frame rather than its retained predecessor", () => {
  const pausedStep = loadScript({
    historyValues: ["11111111-1111-4111-8111-111111111111:old", "*22222222-2222-4222-8222-222222222222:current"],
    frames: [
      { images: ["data:image/jpeg;base64,old"] },
      { images: ["data:image/jpeg;base64,current"] },
    ],
  });
  pausedStep.port.listener({ type: "pause", paused: true });
  pausedStep.port.listener({ type: "step", forward: true });
  pausedStep.runMicrotasks();
  assert.equal(pausedStep.nativeMessages[0].assetId, "22222222-2222-4222-8222-222222222222");
});

test("capture retries when the current frame becomes visible after its transition", () => {
  const runtime = loadScript({
    historyValues: ["*22222222-2222-4222-8222-222222222222:current"],
    frames: [{ images: ["data:image/jpeg;base64,current"], visible: false }],
  });
  runtime.runMicrotasks();
  assert.equal(runtime.nativeMessages.length, 0);
  runtime.setFrameVisible(0, true);
  runtime.fireEvent("transitionend");
  runtime.runMicrotasks();
  assert.equal(runtime.nativeMessages[0].assetId, "22222222-2222-4222-8222-222222222222");
});
