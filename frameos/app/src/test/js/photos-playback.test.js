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
  kioskContainer.id = "kiosk-container";
  kioskContainer.classList = { contains: (name) => push && name === "transition-push" };
  const kiosk = makeElement();
  kiosk.id = "kiosk";
  kiosk.parentElement = kioskContainer;
  const progress = makeElement();
  const frameList = (frames || []).map(({ images, visible = true, animations = [] }) => {
    const frame = makeElement(visible);
    frame.parentElement = kiosk;
    frame.closest = (selector) => selector === "#kiosk" ? kiosk : null;
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
    frame.animations = animations.map(({ target = "frame", iterations = 1 }) => {
      const animation = {
        playState: "paused",
        finishCalls: 0,
        effect: {
          target: target === "frame" ? frame : makeElement(),
          getTiming() { return { iterations }; },
        },
        finish() {
          this.finishCalls += 1;
          this.playState = "finished";
          if (target === "frame") frame.visible = true;
        },
      };
      return animation;
    });
    frame.getAnimations = () => frame.animations;
    return frame;
  });
  kioskContainer.querySelector = (selector) => selector === ":scope > #kiosk" ? kiosk : null;
  kiosk.querySelectorAll = (selector) => selector === ":scope > .frame" ? frameList : [];
  kiosk.contains = (element) => element === kiosk || frameList.includes(element) || frameList.some((frame) => frame.images.includes(element));
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
    body, events, next, previous, port, timers, observers, listeners, classes, nativeMessages, progress, kiosk,
    runMicrotasks() { while (microtasks.length) microtasks.shift()(); },
    pendingMicrotasks() { return microtasks.length; },
    setFrameVisible(index, visible) { frameList[index].visible = visible; },
    frameAnimations(index) { return frameList[index].animations; },
    frame(index) { return frameList[index]; },
    mutate(records) { observers.find((observer) => observer.target === context.document.documentElement).callback(records); },
    fireEvent(type, detail = {}, target = detail.target) {
      listeners.filter(([eventType]) => eventType === type).forEach(([, listener]) => listener({ type, detail, target }));
    },
    setHistory(values) {
      history.splice(0, history.length, ...values.map((value) => ({ value })));
    },
    setFrameImage(index, source) {
      const image = frameList[index].images[0];
      image.currentSrc = source;
      image.src = source;
    },
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
  assert.deepEqual(runtime.listeners.map(([type]) => type), [
    "load", "transitionend", "animationend",
    "htmx:beforeSend", "htmx:afterSettle", "htmx:responseError", "htmx:sendError", "htmx:sendAbort", "htmx:timeout", "htmx:afterRequest",
  ]);
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

test("paused capture finishes only the current frame's finite entrance animation", () => {
  const runtime = loadScript({
    historyValues: ["*22222222-2222-4222-8222-222222222222:current"],
    frames: [{
      images: ["data:image/jpeg;base64,current"],
      visible: false,
      animations: [{ target: "frame" }, { target: "descendant" }],
    }],
  });
  runtime.port.listener({ type: "pause", paused: true });
  runtime.runMicrotasks();
  const [entrance, descendant] = runtime.frameAnimations(0);
  assert.equal(entrance.finishCalls, 1);
  assert.equal(descendant.finishCalls, 0);
  assert.equal(runtime.nativeMessages[0].assetId, "22222222-2222-4222-8222-222222222222");
  runtime.fireEvent("animationend");
  runtime.runMicrotasks();
  assert.equal(entrance.finishCalls, 1);
});

test("capture ignores progress bar RAF styles but keeps Kiosk visibility mutations", () => {
  const runtime = loadScript({
    historyValues: ["*22222222-2222-4222-8222-222222222222:current"],
    frames: [{ images: ["data:image/jpeg;base64,current"] }],
  });
  runtime.runMicrotasks();
  runtime.mutate([{ type: "attributes", attributeName: "style", target: runtime.progress }]);
  assert.equal(runtime.pendingMicrotasks(), 0);
  runtime.mutate([{ type: "attributes", attributeName: "style", target: runtime.frame(0) }]);
  assert.equal(runtime.pendingMicrotasks(), 1);
});

test("HTMX transaction gating never pairs split frame and history swaps", () => {
  const oldId = "11111111-1111-4111-8111-111111111111";
  const newId = "22222222-2222-4222-8222-222222222222";
  for (const historyFirst of [true, false]) {
    const runtime = loadScript({
      historyValues: [`*${oldId}:old`],
      frames: [{ images: ["data:image/jpeg;base64,old"] }],
    });
    const request = {};
    runtime.runMicrotasks();
    runtime.fireEvent("htmx:beforeSend", { target: runtime.kiosk, xhr: request });
    if (historyFirst) {
      runtime.setHistory([`${oldId}:old`, `*${newId}:new`]);
      runtime.mutate([{ type: "attributes", attributeName: "value", target: {} }]);
      runtime.runMicrotasks();
      runtime.setFrameImage(0, "data:image/jpeg;base64,new");
    } else {
      runtime.setFrameImage(0, "data:image/jpeg;base64,new");
      runtime.mutate([{ type: "childList", target: runtime.frame(0) }]);
      runtime.runMicrotasks();
      runtime.setHistory([`${oldId}:old`, `*${newId}:new`]);
    }
    runtime.mutate([{ type: "childList", target: runtime.frame(0) }]);
    runtime.fireEvent("htmx:afterSettle", { target: runtime.kiosk, xhr: request }, runtime.kiosk);
    runtime.runMicrotasks();
    assert.deepEqual(
      runtime.nativeMessages.map(({ assetId, image }) => ({ assetId, image })),
      [
        { assetId: oldId, image: "old" },
        { assetId: newId, image: "new" },
      ],
    );
  }
});

test("a capture queued before Kiosk HTMX starts cannot run until that request settles", () => {
  const oldId = "11111111-1111-4111-8111-111111111111";
  const newId = "22222222-2222-4222-8222-222222222222";
  const runtime = loadScript({ historyValues: [`*${oldId}:old`], frames: [{ images: ["data:image/jpeg;base64,old"] }] });
  const request = {};
  runtime.runMicrotasks();
  runtime.setHistory([`${oldId}:old`, `*${newId}:new`]);
  runtime.setFrameImage(0, "data:image/jpeg;base64,new");
  runtime.mutate([{ type: "childList", target: runtime.frame(0) }]);
  runtime.fireEvent("htmx:beforeSend", { target: runtime.kiosk, xhr: request });
  runtime.runMicrotasks();
  assert.equal(runtime.nativeMessages.length, 1);
  runtime.fireEvent("htmx:afterSettle", { target: runtime.kiosk, xhr: request }, runtime.kiosk);
  runtime.runMicrotasks();
  assert.equal(runtime.nativeMessages[1].assetId, newId);
  assert.equal(runtime.nativeMessages[1].image, "new");
});

test("failed Kiosk HTMX does not release capture until a later successful transaction", () => {
  const oldId = "11111111-1111-4111-8111-111111111111";
  const newId = "22222222-2222-4222-8222-222222222222";
  const finalId = "33333333-3333-4333-8333-333333333333";
  const runtime = loadScript({ historyValues: [`*${oldId}:old`], frames: [{ images: ["data:image/jpeg;base64,old"] }] });
  const failed = {};
  runtime.runMicrotasks();
  runtime.fireEvent("htmx:beforeSend", { target: runtime.kiosk, xhr: failed });
  runtime.setHistory([`${oldId}:old`, `*${newId}:new`]);
  runtime.setFrameImage(0, "data:image/jpeg;base64,new");
  runtime.mutate([{ type: "childList", target: runtime.frame(0) }]);
  runtime.fireEvent("htmx:sendError", { target: runtime.kiosk, xhr: failed });
  runtime.runMicrotasks();
  assert.equal(runtime.nativeMessages.length, 1);
  const successful = {};
  runtime.fireEvent("htmx:beforeSend", { target: runtime.kiosk, xhr: successful });
  runtime.setHistory([`${newId}:new`, `*${finalId}:final`]);
  runtime.setFrameImage(0, "data:image/jpeg;base64,final");
  runtime.mutate([{ type: "childList", target: runtime.frame(0) }]);
  runtime.fireEvent("htmx:afterSettle", { target: runtime.kiosk, xhr: successful }, runtime.kiosk);
  runtime.runMicrotasks();
  assert.deepEqual(
    runtime.nativeMessages.slice(-1).map(({ type, assetId, image }) => ({ type, assetId, image })),
    [{ type: "loaded-photo", assetId: finalId, image: "final" }],
  );
});

test("overlapping Kiosk settles retain capture until every response settles", () => {
  const firstId = "11111111-1111-4111-8111-111111111111";
  const secondId = "22222222-2222-4222-8222-222222222222";
  const thirdId = "33333333-3333-4333-8333-333333333333";
  const runtime = loadScript({ historyValues: [`*${firstId}:first`], frames: [{ images: ["data:image/jpeg;base64,first"] }] });
  const requestA = {};
  const requestB = {};
  runtime.runMicrotasks();
  runtime.fireEvent("htmx:beforeSend", { target: runtime.kiosk, xhr: requestA });
  runtime.setHistory([`${firstId}:first`, `*${secondId}:second`]);
  runtime.setFrameImage(0, "data:image/jpeg;base64,second");
  runtime.mutate([{ type: "childList", target: runtime.frame(0) }]);
  runtime.fireEvent("htmx:beforeSend", { target: runtime.kiosk, xhr: requestB });
  runtime.setHistory([`${secondId}:second`, `*${thirdId}:third`]);
  runtime.setFrameImage(0, "data:image/jpeg;base64,third");
  runtime.mutate([{ type: "childList", target: runtime.frame(0) }]);
  runtime.fireEvent("htmx:afterSettle", { target: runtime.kiosk, xhr: requestB }, runtime.kiosk);
  runtime.runMicrotasks();
  assert.equal(runtime.nativeMessages.length, 1);
  runtime.fireEvent("htmx:afterSettle", { target: runtime.kiosk, xhr: requestA }, runtime.kiosk);
  runtime.runMicrotasks();
  assert.deepEqual(
    runtime.nativeMessages.slice(-1).map(({ assetId, image }) => ({ assetId, image })),
    [{ assetId: thirdId, image: "third" }],
  );
});
