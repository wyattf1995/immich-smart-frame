"use strict";

// This is the sole global-extension message. It contains no token, camera, or
// user data; native uses it only to discover the opaque moz-extension origin.
browser.runtime.sendNativeMessage("frame_live_camera", {
  type: "live-extension-page",
  url: browser.runtime.getURL("live.html"),
}).catch(() => {});
