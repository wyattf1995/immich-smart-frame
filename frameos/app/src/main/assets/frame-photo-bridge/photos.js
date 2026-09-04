"use strict";

const ASSET_ID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const MAX_BASE64_CHARS = 4 * 1024 * 1024;
let lastFingerprint = "";
let queued = false;

function capture() {
  queued = false;
  const image = document.querySelector("img[alt='Main image']");
  const history = document.querySelector("#kiosk-history input[name='history']");
  if (!(image instanceof HTMLImageElement) || !history) return;
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
  if (fingerprint === lastFingerprint) return;
  lastFingerprint = fingerprint;
  browser.runtime.sendNativeMessage("frame_photo_bridge", {
    type: "loaded-photo",
    assetId,
    image: base64,
  }).catch(() => { lastFingerprint = ""; });
}

function scheduleCapture() {
  if (queued) return;
  queued = true;
  queueMicrotask(capture);
}

document.addEventListener("load", scheduleCapture, true);
new MutationObserver(scheduleCapture).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ["src", "value"] });
scheduleCapture();
