import assert from "node:assert/strict";
import fs from "node:fs";

const bridge = fs.readFileSync(
  "frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraBridge.kt",
  "utf8",
);
const manifest = fs.readFileSync(
  "frameos/touchframe/src/main/assets/frame-live-camera/manifest.json",
  "utf8",
);
const surface = fs.readFileSync(
  "frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraSurface.kt",
  "utf8",
);
const strings = fs.readFileSync("frameos/touchframe/src/main/res/values/touch_media_strings.xml", "utf8");

// The installed extension's metadata is the only source for its packaged page.
// A background handshake could acknowledge once, then strand a later surface.
assert.match(bridge, /installed\.metaData\.baseUrl/);
assert.match(bridge, /resolveLivePage\(installed\.metaData\.baseUrl\)/);
assert.doesNotMatch(bridge, /PAGE_ANNOUNCEMENT|acceptPageAnnouncement/);
assert.doesNotMatch(manifest, /"background"/);
assert.match(bridge, /sender\.webExtension\.id != EXTENSION_ID/);
assert.match(bridge, /sender\.url\.isNotBlank\(\) && stripFragment\(sender\.url\) != expected/);
assert.match(bridge, /worker\.shutdown\(\)/, "close keeps an accepted task runnable to reject its GeckoResult");
assert.match(bridge, /catch \(_: java\.util\.concurrent\.RejectedExecutionException\)/, "a close race cannot leave token handoff unresolved");
assert.match(bridge, /import android\.util\.Base64/, "Android 6 nonce generation uses the platform encoder");
assert.match(bridge, /Base64\.URL_SAFE or Base64\.NO_WRAP or Base64\.NO_PADDING/, "nonce fragments stay URL-safe and padding-free");
assert.doesNotMatch(bridge, /java\.util\.Base64/, "API 26 java.util Base64 must not enter the API 23 target");
assert.match(surface, /@SuppressLint\("ViewConstructor"\)/, "dependency-bound live surface is never XML-inflated");
assert.match(surface, /never inflated from XML/, "ViewConstructor suppression documents the programmatic-only constructor");
assert.doesNotMatch(strings, /name="touch_camera_refresh"/, "unused snapshot refresh string is removed");
console.log("touch live extension packaged-page binding: PASS");
