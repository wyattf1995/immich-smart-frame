"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function loadScript({ connectNative, historyValues = [], images = [] } = {}) {
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
  const mainImages = images.map((image) => Object.assign(new HTMLImageElement(), {
    complete: true,
    naturalWidth: 100,
    naturalHeight: 100,
    currentSrc: image,
    src: image,
    getBoundingClientRect() { return { width: 100, height: 100 }; },
  }));
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
        if (selector === "img[alt='Main image']") return mainImages[0] || null;
        if (selector === "#kiosk-history input[name='history']") return history[0] || null;
        if (selector === ".navigation--next-asset") return next;
        if (selector === ".navigation--prev-asset") return previous;
        return null;
      },
      querySelectorAll(selector) {
        if (selector === "img[alt='Main image']") return mainImages;
        if (selector === "#kiosk-history input[name='history']") return history;
        return [];
      },
    },
    getComputedStyle() { return { display: "block", visibility: "visible", opacity: "1" }; },
    queueMicrotask(callback) { microtasks.push(callback); },
    setTimeout(callback, delay) { timers.push({ callback, delay }); return timers.length; },
    clearTimeout() {},
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../../main/assets/frame-photo-bridge/photos.js"), "utf8"), context);
  return {
    body, events, next, previous, port, timers, observers, listeners, classes, nativeMessages,
    runMicrotasks() { while (microtasks.length) microtasks.shift()(); },
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

test("capture pairs the unique marked current history with the sole visible main image", () => {
  const runtime = loadScript({
    historyValues: ["11111111-1111-4111-8111-111111111111:old", "*22222222-2222-4222-8222-222222222222:current"],
    images: ["data:image/jpeg;base64,abc"],
  });
  runtime.runMicrotasks();
  assert.equal(runtime.nativeMessages.length, 1);
  assert.equal(runtime.nativeMessages[0].assetId, "22222222-2222-4222-8222-222222222222");
});

test("capture accepts a marked current history entry in the middle and rejects missing or duplicate current entries", () => {
  const middle = loadScript({
    historyValues: ["11111111-1111-4111-8111-111111111111:old", "*22222222-2222-4222-8222-222222222222:current", "33333333-3333-4333-8333-333333333333:old"],
    images: ["data:image/jpeg;base64,abc"],
  });
  middle.runMicrotasks();
  assert.equal(middle.nativeMessages[0].assetId, "22222222-2222-4222-8222-222222222222");
  for (const historyValues of [
    ["11111111-1111-4111-8111-111111111111:old"],
    ["*11111111-1111-4111-8111-111111111111:one", "*22222222-2222-4222-8222-222222222222:two"],
  ]) {
    const rejected = loadScript({ historyValues, images: ["data:image/jpeg;base64,abc"] });
    rejected.runMicrotasks();
    assert.equal(rejected.nativeMessages.length, 0);
  }
});

test("capture rejects retained competing main images and still captures the active asset after a paused step", () => {
  const ambiguous = loadScript({
    historyValues: ["*22222222-2222-4222-8222-222222222222:current"],
    images: ["data:image/jpeg;base64,old", "data:image/jpeg;base64,new"],
  });
  ambiguous.runMicrotasks();
  assert.equal(ambiguous.nativeMessages.length, 0);

  const pausedStep = loadScript({
    historyValues: ["11111111-1111-4111-8111-111111111111:old", "*22222222-2222-4222-8222-222222222222:current"],
    images: ["data:image/jpeg;base64,new"],
  });
  pausedStep.port.listener({ type: "pause", paused: true });
  pausedStep.port.listener({ type: "step", forward: true });
  pausedStep.runMicrotasks();
  assert.equal(pausedStep.nativeMessages[0].assetId, "22222222-2222-4222-8222-222222222222");
});
