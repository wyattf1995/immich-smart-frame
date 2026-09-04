"use strict";

const ASSET_ID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const MAX_BASE64_CHARS = 4 * 1024 * 1024;
const MAX_REJECTED_RETRIES = 3;
const RETRY_DELAY_MS = 1000;
let lastFingerprint = "";
let inFlightFingerprint = "";
let rejectedFingerprint = "";
let rejectedRetries = 0;
let queued = false;
let retryTimer = 0;

function capture() {
  queued = false;
  const image = document.querySelector("img[alt='Main image']");
  const history = document.querySelector("#kiosk-history input[name='history']");
  if (!(image instanceof HTMLImageElement) || !history) return;
  if (!image.complete || image.naturalWidth <= 0 || image.naturalHeight <= 0) return;
  const current = history.value.match(/^\*([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}):[^:]*$/i);
  if (!current) return;
  const assetId = current[1];
  const source = image.currentSrc || image.src;
  const prefix = "data:image/jpeg;base64,";
  const box = image.getBoundingClientRect();
  const style = getComputedStyle(image);
  if (!ASSET_ID.test(assetId) || !source.startsWith(prefix) || box.width <= 0 || box.height <= 0 || style.display === "none" || style.visibility === "hidden" || style.opacity === "0") return;
  const base64 = source.slice(prefix.length);
  if (!base64 || base64.length > MAX_BASE64_CHARS) return;
  const fingerprint = `${assetId}:${base64.length}:${base64.slice(0, 24)}`;
  if (fingerprint === lastFingerprint || fingerprint === inFlightFingerprint) return;
  inFlightFingerprint = fingerprint;
  browser.runtime.sendNativeMessage("frame_photo_bridge", {
    type: "loaded-photo",
    assetId,
    image: base64,
  }).then((response) => {
    inFlightFingerprint = "";
    if (response && response.accepted) {
      lastFingerprint = fingerprint;
      rejectedFingerprint = "";
      rejectedRetries = 0;
      return;
    }
    retryRejectedCapture(fingerprint);
  }).catch(() => {
    inFlightFingerprint = "";
    retryRejectedCapture(fingerprint);
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
  if (queued) return;
  queued = true;
  queueMicrotask(capture);
}

document.addEventListener("load", scheduleCapture, true);
new MutationObserver(scheduleCapture).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ["src", "value"] });
scheduleCapture();
