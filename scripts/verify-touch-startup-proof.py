#!/usr/bin/env python3
"""Prove HOME routing selects Photos while launcher and navigation stay untouched."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

if not __debug__:
    raise RuntimeError("optimized Python is not permitted")
if len(sys.argv) != 2:
    raise SystemExit(f"usage: {Path(sys.argv[0]).name} <repository-root>")

root = Path(sys.argv[1]).resolve()
frame = root / "frameos"
module = frame / "touchframe"
manifest = module / "src/main/AndroidManifest.xml"
activity = module / "src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt"
policy = module / "src/main/kotlin/com/wyattfleming/touchframe/ui/TouchStartupPolicy.kt"
test = module / "src/test/kotlin/com/wyattfleming/touchframe/ui/TouchStartupPolicyTest.kt"
result_dir = module / "build/test-results/testDebugUnitTest"
out = root / "reports/touchframe-android/startup-proof"
out.mkdir(parents=True, exist_ok=True)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_once(text: str, before: str, after: str) -> str:
    if text.count(before) != 1:
        raise RuntimeError(f"expected one guarded selector, found {text.count(before)}: {before!r}")
    return text.replace(before, after)


def run(label: str) -> tuple[int, str]:
    if result_dir.exists():
        shutil.rmtree(result_dir)
    command = [
        "./gradlew", "--no-daemon", "--max-workers=2", "--rerun-tasks",
        ":touchframe:testDebugUnitTest",
        "--tests", "com.wyattfleming.touchframe.ui.TouchStartupPolicyTest",
    ]
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    report = "\n".join(path.read_text(errors="replace") for path in result_dir.rglob("TEST-*.xml")) if result_dir.exists() else ""
    return result.returncode, report + "\n" + log.read_text(errors="replace")


for path in (manifest, activity, policy, test):
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")

manifest_text = manifest.read_text()
activity_text = activity.read_text()
policy_text = policy.read_text()
test_hash = digest(test)
required = {
    "separate Android HOME filter": '<category android:name="android.intent.category.HOME" />',
    "HOME filter is DEFAULT-matchable": '<category android:name="android.intent.category.DEFAULT" />',
    "startup policy is used at creation": "handleStartupIntent(intent)",
    "startup policy handles reused task intents": "override fun onNewIntent(intent: Intent)",
    "reused task intent is recorded": "setIntent(intent)",
    "HOME closes an active sign-in overlay": "closeAuthorization(restoreDestination = false)",
    "HOME routes through ordinary navigation": "navigate(destination)",
    "policy requires Android MAIN": 'action == "android.intent.action.MAIN"',
    "policy requires Android HOME": '"android.intent.category.HOME" in categories',
    "policy opens Photos": "TouchDestination.PHOTOS",
}
combined = manifest_text + activity_text + policy_text
missing = [name for name, needle in required.items() if needle not in combined]
if missing:
    raise RuntimeError(f"missing startup contract markers: {missing}")

try:
    green_before, initial = run("baseline-green")
    if green_before != 0:
        raise RuntimeError(f"startup baseline failed\n{initial[-12000:]}")

    policy.write_text(replace_once(policy_text, "return TouchDestination.PHOTOS", "return null"))
    red_code, red = run("policy-source-red")
    if red_code == 0 or "system Home action opens Photos" not in red:
        raise RuntimeError(f"removing only HOME-to-Photos behavior did not fail its exact test\n{red[-12000:]}")
finally:
    policy.write_text(policy_text)
    if policy.read_text() != policy_text:
        raise RuntimeError("startup policy was not restored exactly")

green_after, restored = run("restored-green")
if green_after != 0:
    raise RuntimeError(f"startup restored baseline failed\n{restored[-12000:]}")
if digest(test) != test_hash:
    raise RuntimeError("startup test changed during source-only proof")

(out / "receipt.json").write_text(json.dumps({
    "passed": True,
    "negative": "remove TouchDestination.PHOTOS only from TouchStartupPolicy",
    "expectedFailure": "system Home action opens Photos",
    "restoredGreen": True,
    "sources": {str(path.relative_to(root)): digest(path) for path in (manifest, activity, policy)},
    "unchangedTest": {str(test.relative_to(root)): test_hash},
}, indent=2) + "\n")
print("Touch startup HOME-to-Photos source red / exact restore green verified")
