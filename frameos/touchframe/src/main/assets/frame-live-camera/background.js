"use strict";

// This is the sole global-extension message. It contains no token, camera, or
// user data; native uses it only to discover the opaque moz-extension origin.
// A built-in extension may start before the session-specific delegate exists,
// so retry until that delegate acknowledges the static page URL.
function announce() {
  browser.runtime.sendNativeMessage("frame_live_camera", {
    type: "live-extension-page",
    url: browser.runtime.getURL("live.html"),
  }).then(response => {
    if (!response?.accepted) setTimeout(announce, 100);
  }, () => setTimeout(announce, 100));
}
announce();
