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
console.log("touch live extension packaged-page binding: PASS");
