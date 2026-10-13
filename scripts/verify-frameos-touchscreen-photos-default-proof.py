#!/usr/bin/env python3
"""Prove fresh touchscreen settings start Photos while explicit Home remains valid."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree

if len(sys.argv) != 2:
    raise SystemExit(f"usage: {Path(sys.argv[0]).name} <repository-root>")

root = Path(sys.argv[1]).resolve()
frame = root / "frameos"
out = root / "reports" / "frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
report_dir = frame / "app/build/test-results/testDebugUnitTest"
sources = {
    "dto": frame / "app/src/main/kotlin/com/wyattfleming/frameos/config/FrameTouchscreenSettings.kt",
    "store": frame / "app/src/main/kotlin/com/wyattfleming/frameos/config/FrameTouchscreenSettingsStore.kt",
}
tests = {
    "settings": (frame / "app/src/test/kotlin/com/wyattfleming/frameos/config/FrameTouchscreenSettingsTest.kt", 4),
    "store": (frame / "app/src/test/kotlin/com/wyattfleming/frameos/config/FrameTouchscreenSettingsStoreContractTest.kt", 1),
}
result_names = {
    "settings": "TEST-com.wyattfleming.frameos.config.FrameTouchscreenSettingsTest.xml",
    "store": "TEST-com.wyattfleming.frameos.config.FrameTouchscreenSettingsStoreContractTest.xml",
}
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest",
    "--tests", "com.wyattfleming.frameos.config.FrameTouchscreenSettingsTest",
    "--tests", "com.wyattfleming.frameos.config.FrameTouchscreenSettingsStoreContractTest",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(label: str, expected_failure: str | None) -> dict:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"touchscreen-photos-default-{label}.log"
    with log.open("w") as stream:
        completed = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    total_cases = total_failures = 0
    xml = {}
    failed_names = set()
    for name, (_, expected_cases) in tests.items():
        reports = list(report_dir.rglob(result_names[name]))
        if len(reports) != 1:
            raise RuntimeError(f"expected one {name} JUnit report, found {reports}")
        copied = out / f"touchscreen-photos-default-{label}-{name}.xml"
        shutil.copy2(reports[0], copied)
        cases = list(ElementTree.parse(copied).getroot().iter("testcase"))
        failures = [case for case in cases if case.find("failure") is not None]
        errors = [case for case in cases if case.find("error") is not None]
        skipped = [case for case in cases if case.find("skipped") is not None]
        if len(cases) != expected_cases or errors or skipped:
            raise RuntimeError(f"unexpected {name} JUnit result")
        if any(case.find("failure").get("type") != "java.lang.AssertionError" for case in failures):
            raise RuntimeError(f"{name} failed outside its assertion")
        total_cases += len(cases)
        total_failures += len(failures)
        failed_names.update(case.get("name") for case in failures)
        xml[name] = copied.name
    if total_cases != 5 or total_failures != (0 if expected_failure is None else 1):
        raise RuntimeError(f"unexpected test counts cases={total_cases} failures={total_failures}")
    if expected_failure is None:
        if completed.returncode != 0 or failed_names:
            raise RuntimeError(log.read_text())
    elif completed.returncode == 0 or failed_names != {expected_failure}:
        raise RuntimeError(f"expected only {expected_failure!r}, got {failed_names}")
    return {"exit": completed.returncode, "tests": total_cases, "failures": total_failures, "log": log.name, "xml": xml}


for path in [*sources.values(), *(path for path, _ in tests.values())]:
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")
originals = {name: path.read_bytes() for name, path in sources.items()}
source_hashes = {name: sha256(path) for name, path in sources.items()}
test_hashes = {name: sha256(path) for name, (path, _) in tests.items()}
checks = (
    ("dto", "val startWithPhotos: Boolean = true", "val startWithPhotos: Boolean = false", "defaults keep dashboard and hidden Home detachment opted out but start Photos"),
    ("store", 'preferences.getBoolean("start_with_photos", true)', 'preferences.getBoolean("start_with_photos", false)', "missing touchscreen startup preference falls through to Photos"),
)
results = {"baseline": run("baseline-green", None)}
for name, before, after, expected_failure in checks:
    source = sources[name]
    original = originals[name]
    try:
        text = original.decode("utf-8")
        if text.count(before) != 1:
            raise RuntimeError(f"expected one untouched marker for {name}")
        source.write_text(text.replace(before, after, 1), encoding="utf-8")
        results[f"{name}_red"] = run(f"{name}-red", expected_failure)
    finally:
        source.write_bytes(original)
        if sha256(source) != source_hashes[name]:
            raise RuntimeError(f"exact {name} source restoration failed")
    results[f"{name}_restored"] = run(f"{name}-restored-green", None)

if test_hashes != {name: sha256(path) for name, (path, _) in tests.items()}:
    raise RuntimeError("a guarding test changed during proof")
(out / "touchscreen-photos-default-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "fresh settings default to Photos while explicit false remains represented in the DTO",
    "command": command,
    "results": results,
    "restored_source_sha256": {name: sha256(path) for name, path in sources.items()},
    "unchanged_test_sha256": test_hashes,
}, indent=2) + "\n", encoding="utf-8")
print("touchscreen Photos-default source-only red / exact restore green verified", flush=True)
