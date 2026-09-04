"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function loadScript() {
  const events = [];
  const timers = [];
  const classes = new Set();
  const next = { clicks: 0, click() { this.clicks += 1; } };
  const previous = { clicks: 0, click() { this.clicks += 1; } };
  class HTMLElement {}
  Object.setPrototypeOf(next, HTMLElement.prototype);
  Object.setPrototypeOf(previous, HTMLElement.prototype);
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
    HTMLImageElement: class HTMLImageElement {},
    KeyboardEvent: class KeyboardEvent { constructor(type, init) { this.type = type; Object.assign(this, init); } },
    MutationObserver: class MutationObserver { observe() {} },
    browser: { runtime: { connectNative() { return port; }, sendNativeMessage() { return Promise.resolve({ accepted: false }); } } },
    document: {
      body,
      documentElement: {},
      addEventListener() {},
      querySelector(selector) {
        if (selector === ".navigation--next-asset") return next;
        if (selector === ".navigation--prev-asset") return previous;
        return null;
      },
    },
    getComputedStyle() { return {}; },
    queueMicrotask() {},
    setTimeout(callback) { timers.push(callback); return timers.length; },
    clearTimeout() {},
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../../main/assets/frame-photo-bridge/photos.js"), "utf8"), context);
  return { body, events, next, previous, port, timers };
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
  runtime.body.classList.contains = () => false;
  runtime.timers.forEach((timer) => timer());
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
