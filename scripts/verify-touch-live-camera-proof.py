#!/usr/bin/env python3
"""Static contract for the constrained native-capability live camera path."""
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
bridge = (root / "frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraBridge.kt").read_text()
policy = (root / "frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraCapability.kt").read_text()
manifest = (root / "frameos/touchframe/src/main/assets/frame-live-camera/manifest.json").read_text()
page = (root / "frameos/touchframe/src/main/assets/frame-live-camera/live.js").read_text()

required = {
    "bridge_extension_base_url": "resolveLivePage(installed.metaData.baseUrl)",
    "bridge_session_gate": "senderSession === activeSession && sender.isTopLevel",
    "bridge_single_use": "capability.consume(",
    "bridge_visibility_gate": "capability.isCurrent(current.generation, current.entityId, current.nonce, visible)",
    "bridge_timeout": "START_TIMEOUT_MILLIS = 12_000L",
    "policy_exact_page": 'uri.path == "/live.html"',
    "policy_allowlist": "fun allowsCamera(entityId: String): Boolean = entityId in cameras",
    "page_webrtc_config": 'type: "camera/webrtc/get_client_config"',
    "page_webrtc_offer": 'type: "camera/webrtc/offer"',
    "page_webrtc_candidate": 'type: "camera/webrtc/candidate"',
    "page_actual_event_shape": "this.handleOfferEvent(message.event)",
    "page_nest_data_channel": "this.peer.createDataChannel(config.dataChannel)",
    "page_playing_event": 'this.video.addEventListener("playing"',
    "page_unsubscribe": 'type: "unsubscribe_events"',
    "page_startup_timeout": "STARTUP_TIMEOUT_MS = 12000",
    "page_maximum_lifetime": "MAX_LIVE_MS = 5 * 60 * 1000",
}
sources = {"bridge": bridge, "policy": policy, "page": page}
for name, needle in required.items():
    source = sources["bridge" if name.startswith("bridge") else "policy" if name.startswith("policy") else "page"]
    if needle not in source:
        raise SystemExit(f"missing {name}: {needle}")

for forbidden in ("content_scripts", "nativeMessagingFromContent"):
    if forbidden in manifest:
        raise SystemExit(f"forbidden extension exposure: {forbidden}")
for forbidden in ("localStorage", "sessionStorage", "console.", "?access_token", "#access_token"):
    if forbidden in page:
        raise SystemExit(f"forbidden page token/storage/logging pattern: {forbidden}")
if "access_token" not in bridge or "access_token" not in page:
    raise SystemExit("native response and packaged page must agree on the in-memory token field")
node = shutil.which("node")
if node is None:
    raise SystemExit("node is required for the live WebRTC protocol proof")
subprocess.run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], check=True)
subprocess.run([node, str(root / "scripts/tests/test-touch-live-extension-page.mjs")], check=True)
print("touch live camera source proof: PASS")
