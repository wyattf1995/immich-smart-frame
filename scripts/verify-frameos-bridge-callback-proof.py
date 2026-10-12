#!/usr/bin/env python3
"""Prove GeckoBundle callback marshalling before restoring the exact source."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
frame = root / "frameos"
out = root / "reports" / "frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/photos/FramePhotoBridge.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/photos/FramePhotoBridgeCallbackResponseTest.kt"
original = source.read_bytes()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testReleaseUnitTest",
    "--tests", "com.wyattfleming.frameos.photos.FramePhotoBridgeCallbackResponseTest",
]

before = '''        internal fun acceptanceResponse(accepted: Boolean): GeckoBundle = GeckoBundle(1).apply {
            putBoolean("accepted", accepted)
        }
'''
after = '''        internal fun acceptanceResponse(accepted: Boolean): Any = JSONObject().put("accepted", accepted)
'''

def run(label: str) -> tuple[int, str]:
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    return result.returncode, log.read_text()

try:
    text = original.decode()
    assert text.count(before) == 1
    source.write_text(text.replace(before, after))
    negative_exit, negative_log = run("bridge-callback-source-red")
    assert negative_exit != 0
    assert "acceptance callback uses GeckoBundle" in negative_log
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original

restored_exit, restored_log = run("bridge-callback-restored-green")
assert restored_exit == 0, restored_log
assert hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
(out / "bridge-callback-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "GeckoBundle callback marshalling",
    "negative_exit": negative_exit,
    "restored_green_exit": restored_exit,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
    "negative_log": "bridge-callback-source-red.log",
    "restored_log": "bridge-callback-restored-green.log",
}, indent=2) + "\n")
print("GeckoBundle callback source-only red / exact restore green verified", flush=True)
