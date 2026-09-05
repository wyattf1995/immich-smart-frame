"use strict";

const ASSET_ID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const MAX_BASE64_CHARS = 4 * 1024 * 1024;
const MAX_REJECTED_RETRIES = 3;
const RETRY_DELAY_MS = 1000;
const INITIAL_PORT_RETRY_DELAY_MS = 1000;
const MAX_PORT_RETRY_DELAY_MS = 60000;
const MAX_SETTLING_KIOSK_REQUESTS = 4;
const MAX_DIAGNOSTICS_PER_PAGE = 16;
const MAX_DIAGNOSTIC_EPOCH = 4096;
let lastFingerprint = "";
let inFlightFingerprint = "";
let rejectedFingerprint = "";
let rejectedRetries = 0;
let queued = false;
let retryTimer = 0;
let desiredPlaybackPaused = null;
let pauseSnapshotNonce = null;
let playbackPort = null;
let portRetryDelayMs = INITIAL_PORT_RETRY_DELAY_MS;
let portRetryTimer = 0;
const settlingKioskRequests = new Set();
let kioskCaptureReady = true;
let settledKioskEpoch = 0;
let pauseSnapshotRequiresSettleEpoch = null;
let diagnosticReports = 0;
const firstDiagnosticStages = new Set();

function reportDiagnostic(stage, fields = {}, once = false) {
  if (diagnosticReports >= MAX_DIAGNOSTICS_PER_PAGE || (once && firstDiagnosticStages.has(stage))) return;
  if (once) firstDiagnosticStages.add(stage);
  diagnosticReports += 1;
  browser.runtime.sendNativeMessage("frame_photo_bridge", {
    type: "bridge-diagnostic",
    stage,
    detailReadable: fields.detailReadable === true,
    targetIsKiosk: fields.targetIsKiosk === true,
    xhrPresent: fields.xhrPresent === true,
    primaryTarget: fields.primaryTarget === true,
    desiredPaused: desiredPlaybackPaused === true,
    noncePresent: pauseSnapshotNonce !== null,
    settledEpoch: Math.min(settledKioskEpoch, MAX_DIAGNOSTIC_EPOCH),
    trackedCount: Math.min(settlingKioskRequests.size, MAX_SETTLING_KIOSK_REQUESTS),
  }).catch(() => {});
}

function isVisible(element) {
  for (let current = element; current && current !== document.documentElement; current = current.parentElement) {
    const box = current.getBoundingClientRect();
    const style = getComputedStyle(current);
    if (box.width <= 0 || box.height <= 0 || style.display === "none" || style.visibility === "hidden" || style.opacity === "0") return false;
  }
  return true;
}

function currentFrame() {
  const container = document.querySelector("#kiosk-container");
  if (!(container instanceof HTMLElement)) return null;
  const kiosk = container.querySelector(":scope > #kiosk");
  if (!(kiosk instanceof HTMLElement)) return null;
  const frames = Array.from(kiosk.querySelectorAll(":scope > .frame"));
  if (!frames.length) return null;
  return container.classList.contains("transition-push") ? frames[0] : frames[frames.length - 1];
}

function settlePausedFrame(frame) {
  if (!desiredPlaybackPaused || !document.body.classList.contains("polling-paused")) return;
  for (const animation of frame.getAnimations()) {
    const effect = animation.effect;
    const timing = effect && effect.getTiming();
    if (!effect || effect.target !== frame || !timing || !Number.isFinite(timing.iterations) || animation.playState === "finished") continue;
    try {
      animation.finish();
    } catch (_) {
      // A canceled animation cannot make the frame visible.
    }
  }
}

function capture() {
  queued = false;
  if (settlingKioskRequests.size > 0 || !kioskCaptureReady) return;
  const histories = Array.from(document.querySelectorAll("#kiosk-history input[name='history']"))
    .filter((history) => typeof history.value === "string" && history.value.startsWith("*"));
  if (histories.length !== 1) return;
  const frame = currentFrame();
  if (frame) settlePausedFrame(frame);
  if (!frame || !isVisible(frame)) return;
  const images = Array.from(frame.querySelectorAll("img[alt='Main image']"))
    .filter((image) => image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0 && image.naturalHeight > 0)
    .filter(isVisible);
  if (images.length !== 1) return;
  const image = images[0];
  const current = histories[0].value.match(/^\*([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}):[^:]*$/i);
  if (!current) return;
  const assetId = current[1];
  const source = image.currentSrc || image.src;
  const prefix = "data:image/jpeg;base64,";
  if (!ASSET_ID.test(assetId) || !source.startsWith(prefix)) return;
  const base64 = source.slice(prefix.length);
  if (!base64 || base64.length > MAX_BASE64_CHARS) return;
  const snapshotNonce = desiredPlaybackPaused ? pauseSnapshotNonce : null;
  // A paused document may send only the host-issued one-shot snapshot, never ordinary capture.
  if (desiredPlaybackPaused && !snapshotNonce) return;
  if (snapshotNonce && pauseSnapshotRequiresSettleEpoch !== null && settledKioskEpoch <= pauseSnapshotRequiresSettleEpoch) return;
  const fingerprint = `${assetId}:${base64.length}:${base64.slice(0, 24)}`;
  const captureFingerprint = snapshotNonce ? `${fingerprint}:${snapshotNonce}` : fingerprint;
  if ((!snapshotNonce && fingerprint === lastFingerprint) || captureFingerprint === inFlightFingerprint) return;
  inFlightFingerprint = captureFingerprint;
  const message = { type: "loaded-photo", assetId, image: base64 };
  if (snapshotNonce) message.snapshotNonce = snapshotNonce;
  browser.runtime.sendNativeMessage("frame_photo_bridge", message).then((response) => {
    inFlightFingerprint = "";
    if (response && response.accepted) {
      lastFingerprint = fingerprint;
      if (snapshotNonce === pauseSnapshotNonce) {
        pauseSnapshotNonce = null;
        pauseSnapshotRequiresSettleEpoch = null;
      }
      rejectedFingerprint = "";
      rejectedRetries = 0;
      portRetryDelayMs = INITIAL_PORT_RETRY_DELAY_MS;
      return;
    }
    retryRejectedCapture(captureFingerprint);
  }).catch(() => {
    inFlightFingerprint = "";
    retryRejectedCapture(captureFingerprint);
  });
}

function retryRejectedCapture(fingerprint) {
  if (rejectedFingerprint !== fingerprint) {
    rejectedFingerprint = fingerprint;
    rejectedRetries = 0;
  }
  if (rejectedRetries >= MAX_REJECTED_RETRIES || retryTimer) return;
  rejectedRetries += 1;
  retryTimer = setTimeout(() => {
    retryTimer = 0;
    scheduleCapture();
  }, RETRY_DELAY_MS);
}

function scheduleCapture() {
  if (queued || settlingKioskRequests.size > 0 || !kioskCaptureReady) return;
  queued = true;
  queueMicrotask(capture);
}

function kioskHtmxRequest(event, stage = null) {
  let detailReadable = false;
  let targetIsKiosk = false;
  let xhrPresent = false;
  let primaryTarget = false;
  let request = null;
  try {
    const detail = event.detail;
    targetIsKiosk = !!detail && detail.target instanceof HTMLElement && detail.target.id === "kiosk";
    xhrPresent = !!detail && !!detail.xhr;
    primaryTarget = !!detail && event.target === detail.target;
    request = targetIsKiosk && xhrPresent ? detail.xhr : null;
    detailReadable = true;
  } catch (_) {
    // Page-created HTMX detail can be unreadable from Firefox's isolated content realm.
  }
  const fields = { detailReadable, targetIsKiosk, xhrPresent, primaryTarget };
  if (stage) reportDiagnostic(stage, fields);
  return { request, primaryTarget, fields };
}

function beginKioskHtmxRequest(event) {
  const { request } = kioskHtmxRequest(event, "htmx_before");
  if (!request) return;
  // Kiosk releases its request lock after swap, before settle; retain only a bounded tail of settle-only responses.
  if (settlingKioskRequests.size >= MAX_SETTLING_KIOSK_REQUESTS) settlingKioskRequests.clear();
  settlingKioskRequests.add(request);
  kioskCaptureReady = false;
}

function settleKioskHtmxRequest(event) {
  const { request, primaryTarget } = kioskHtmxRequest(event, "htmx_primary_settle");
  // HTMX settles OOB elements too; only the primary #kiosk target completes its response transaction.
  if (!request || !primaryTarget || !settlingKioskRequests.delete(request)) return;
  if (settlingKioskRequests.size > 0) return;
  kioskCaptureReady = true;
  settledKioskEpoch += 1;
  scheduleCapture();
}

function failKioskHtmxRequest(event, inspected = null) {
  const { request } = inspected || kioskHtmxRequest(event, "htmx_error");
  if (!request || !settlingKioskRequests.delete(request)) return;
  if (settlingKioskRequests.size > 0) return;
  // Wait for another successful Kiosk response; a failed partial response has no trustworthy pair.
  kioskCaptureReady = false;
  if (pauseSnapshotRequiresSettleEpoch !== null) {
    pauseSnapshotNonce = null;
    pauseSnapshotRequiresSettleEpoch = null;
  }
}

function skipNoSwapKioskRequest(event) {
  const inspected = kioskHtmxRequest(event);
  if (inspected.request && inspected.request.status === 204) {
    reportDiagnostic("htmx_error", inspected.fields);
    failKioskHtmxRequest(event, inspected);
  }
}

function isCaptureMutation(mutation) {
  if (mutation.type === "childList") return true;
  if (mutation.type !== "attributes") return false;
  if (mutation.attributeName === "src" || mutation.attributeName === "value") return true;
  if (mutation.attributeName !== "class" && mutation.attributeName !== "style") return false;
  const target = mutation.target;
  if (target === document.documentElement || target === document.body) return true;
  if (!(target instanceof HTMLElement)) return false;
  if (target.id === "kiosk-container" || target.id === "kiosk") return true;
  return typeof target.closest === "function" && target.closest("#kiosk") !== null;
}

function scheduleCaptureMutations(mutations) {
  if (mutations.some(isCaptureMutation)) scheduleCapture();
}

function setPollingPaused(paused) {
  if (document.body.classList.contains("polling-paused") === paused) return;
  document.body.dispatchEvent(new KeyboardEvent("keydown", {
    code: "KeyP",
    key: "p",
    shiftKey: !paused,
    bubbles: true,
  }));
}

function stepPhoto(forward) {
  const selector = forward ? ".navigation--next-asset" : ".navigation--prev-asset";
  const control = document.querySelector(selector);
  if (control instanceof HTMLElement) {
    control.click();
  } else {
    document.body.dispatchEvent(new KeyboardEvent("keyup", {
      key: forward ? "ArrowRight" : "ArrowLeft",
      bubbles: true,
    }));
  }
}

function handlePlaybackCommand(command) {
  if (!command || typeof command.type !== "string") return;
  portRetryDelayMs = INITIAL_PORT_RETRY_DELAY_MS;
  if (command.type === "pause" && typeof command.paused === "boolean") {
    desiredPlaybackPaused = command.paused;
    pauseSnapshotNonce = command.paused && typeof command.snapshotNonce === "string" ? command.snapshotNonce : null;
    pauseSnapshotRequiresSettleEpoch = null;
    reportDiagnostic("pause", {}, true);
    setPollingPaused(command.paused);
    if (command.paused) scheduleCapture();
  } else if (command.type === "step" && typeof command.forward === "boolean") {
    if (desiredPlaybackPaused && typeof command.snapshotNonce === "string") {
      pauseSnapshotNonce = command.snapshotNonce;
      pauseSnapshotRequiresSettleEpoch = settledKioskEpoch;
    }
    reportDiagnostic("step", {}, true);
    stepPhoto(command.forward);
  }
}

function schedulePlaybackReconnect() {
  if (portRetryTimer) return;
  const delay = portRetryDelayMs;
  portRetryDelayMs = Math.min(portRetryDelayMs * 2, MAX_PORT_RETRY_DELAY_MS);
  portRetryTimer = setTimeout(() => {
    portRetryTimer = 0;
    connectPlaybackPort();
  }, delay);
}

function connectPlaybackPort() {
  try {
    const port = browser.runtime.connectNative("frame_photo_bridge");
    if (!port || !port.onMessage || typeof port.onMessage.addListener !== "function") throw new Error("missing native port");
    playbackPort = port;
    port.onMessage.addListener(handlePlaybackCommand);
    if (port.onDisconnect && typeof port.onDisconnect.addListener === "function") {
      port.onDisconnect.addListener(() => {
        playbackPort = null;
        schedulePlaybackReconnect();
      });
    }
  } catch (_) {
    playbackPort = null;
    schedulePlaybackReconnect();
  }
}

new MutationObserver(() => {
  if (desiredPlaybackPaused && !document.body.classList.contains("polling-paused")) setPollingPaused(true);
}).observe(document.body, { attributes: true, attributeFilter: ["class"] });

document.addEventListener("load", scheduleCapture, true);
document.addEventListener("transitionend", scheduleCapture, true);
document.addEventListener("animationend", scheduleCapture, true);
document.addEventListener("htmx:beforeSend", beginKioskHtmxRequest, true);
document.addEventListener("htmx:afterSettle", settleKioskHtmxRequest, true);
document.addEventListener("htmx:responseError", failKioskHtmxRequest, true);
document.addEventListener("htmx:sendError", failKioskHtmxRequest, true);
document.addEventListener("htmx:sendAbort", failKioskHtmxRequest, true);
document.addEventListener("htmx:timeout", failKioskHtmxRequest, true);
document.addEventListener("htmx:afterRequest", skipNoSwapKioskRequest, true);
new MutationObserver(scheduleCaptureMutations).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ["src", "value", "class", "style"] });
scheduleCapture();
connectPlaybackPort();
