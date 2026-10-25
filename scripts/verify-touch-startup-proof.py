#!/usr/bin/env python3
"""Prove HOME routing selects Photos while launcher and navigation stay untouched."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

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


def junit_summary(label: str, expected_failures: int) -> dict[str, object]:
    reports = list(result_dir.rglob("TEST-*.xml")) if result_dir.exists() else []
    if len(reports) != 1:
        raise RuntimeError(f"expected exactly one JUnit XML report, found {reports}")
    copied = out / f"{label}.xml"
    shutil.copy2(reports[0], copied)
    suite = ET.parse(copied).getroot()
    cases = list(suite.iter("testcase"))
    failures = [(case, node) for case in cases for node in case.findall("failure")]
    errors = [(case, node) for case in cases for node in case.findall("error")]
    skipped = [node for case in cases for node in case.findall("skipped")]
    if len(cases) != 3 or len(failures) != expected_failures or errors or skipped:
        raise RuntimeError(
            f"unexpected JUnit summary: tests={len(cases)} failures={len(failures)} "
            f"errors={len(errors)} skipped={len(skipped)}"
        )
    return {
        "xml": copied.name,
        "tests": len(cases),
        "failures": len(failures),
        "errors": len(errors),
        "skipped": len(skipped),
        "failed_cases": [case.get("name") for case, _ in failures],
    }


def run(label: str, expected_failures: int) -> tuple[int, str, dict[str, object]]:
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
    return result.returncode, log.read_text(errors="replace"), junit_summary(label, expected_failures)


for path in (manifest, activity, policy, test):
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")

manifest_text = manifest.read_text()
activity_text = activity.read_text()
policy_text = policy.read_text()
test_hash = digest(test)
android = "{http://schemas.android.com/apk/res/android}"
home_filters = []
for candidate in ET.parse(manifest).getroot().findall(".//activity/intent-filter"):
    actions = {node.get(android + "name") for node in candidate.findall("action")}
    categories = {node.get(android + "name") for node in candidate.findall("category")}
    if "android.intent.action.MAIN" in actions and "android.intent.category.HOME" in categories:
        home_filters.append(categories)
if home_filters != [{"android.intent.category.HOME", "android.intent.category.DEFAULT"}]:
    raise RuntimeError(f"expected one dedicated MAIN/HOME/DEFAULT intent filter, found {home_filters}")
if activity_text.count("handleStartupIntent(intent)") != 2:
    raise RuntimeError("startup intent must be handled once on creation and once for a reused task")
required = {
    "startup policy handles reused task intents": "override fun onNewIntent(intent: Intent)",
    "reused task intent is recorded": "setIntent(intent)",
    "HOME closes an active sign-in overlay": "closeAuthorization(restoreDestination = false)",
    "HOME routes through ordinary navigation": "navigate(startup)",
    "policy rejects non-Android MAIN": 'action != "android.intent.action.MAIN"',
    "policy rejects intents without Android HOME": '"android.intent.category.HOME" !in categories',
    "policy opens Photos": "TouchDestination.PHOTOS",
}
combined = manifest_text + activity_text + policy_text
missing = [name for name, needle in required.items() if needle not in combined]
if missing:
    raise RuntimeError(f"missing startup contract markers: {missing}")

try:
    green_before, initial_log, initial_junit = run("baseline-green", expected_failures=0)
    if green_before != 0:
        raise RuntimeError(f"startup baseline failed\n{initial_log[-12000:]}")

    policy.write_text(replace_once(policy_text, "return TouchDestination.PHOTOS", "return null"))
    red_code, red_log, red_junit = run("policy-source-red", expected_failures=1)
    if red_code == 0 or red_junit["failed_cases"] != ["system Home action opens Photos"]:
        raise RuntimeError(
            "removing only HOME-to-Photos behavior did not fail its exact named assertion\n"
            f"junit={red_junit}\n{red_log[-12000:]}"
        )
finally:
    policy.write_text(policy_text)
    if policy.read_text() != policy_text:
        raise RuntimeError("startup policy was not restored exactly")

green_after, restored_log, restored_junit = run("restored-green", expected_failures=0)
if green_after != 0:
    raise RuntimeError(f"startup restored baseline failed\n{restored_log[-12000:]}")
if digest(test) != test_hash:
    raise RuntimeError("startup test changed during source-only proof")

(out / "receipt.json").write_text(json.dumps({
    "passed": True,
    "negative": "remove TouchDestination.PHOTOS only from TouchStartupPolicy",
    "expectedFailure": "system Home action opens Photos",
    "baselineJUnit": initial_junit,
    "negativeJUnit": red_junit,
    "restoredJUnit": restored_junit,
    "restoredGreen": True,
    "sources": {str(path.relative_to(root)): digest(path) for path in (manifest, activity, policy)},
    "unchangedTest": {str(test.relative_to(root)): test_hash},
}, indent=2) + "\n")
print("Touch startup HOME-to-Photos source red / exact restore green verified")
