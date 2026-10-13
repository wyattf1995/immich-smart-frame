#!/usr/bin/env python3
"""Source-only red/green checks for FrameOS web performance guards."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
reports = root / "reports"
reports.mkdir(exist_ok=True)
panel = root / "examples/frameos/frameos-touchscreen-panel.html"
custom_home = root / "examples/frameos/frameos-custom-home.js"
bridge = root / "frameos/app/src/main/assets/frame-photo-bridge/photos.js"
contract = root / "scripts/tests/test-frameos-web-performance-contract.mjs"
clock_contract = root / "scripts/tests/test-frameos-home-clock-only-contract.mjs"
bridge_vm = root / "scripts/tests/test-frameos-bridge-mutation-vm.mjs"

source_files = (panel, custom_home, bridge)
test_files = (contract, clock_contract, bridge_vm)
source_before = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_files}
test_before = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in test_files}

def run(label, command, expect_success):
    result = subprocess.run(command, cwd=root, text=True, capture_output=True)
    (reports / f"{label}.log").write_text(result.stdout + result.stderr)
    if (result.returncode == 0) != expect_success:
        raise AssertionError(f"{label}: expected success={expect_success}, got {result.returncode}")
    return result.returncode

def mutate_and_restore(path, needle, replacement, command, label):
    original = path.read_bytes()
    if original.count(needle) != 1:
        raise AssertionError(f"{label}: mutation needle must occur exactly once")
    try:
        path.write_bytes(original.replace(needle, replacement))
        run(f"{label}-red", command, expect_success=False)
    finally:
        path.write_bytes(original)
    run(f"{label}-green", command, expect_success=True)

mutate_and_restore(
    panel,
    b"homeProjectionCache?.states === live.states",
    b"false",
    ["node", str(contract)],
    "frameos-web-performance",
)
mutate_and_restore(
    custom_home,
    b"if (!force && modelKey === lastModelKey) { updateClock(now); return; }",
    b"if (false) { updateClock(now); return; }",
    ["node", str(clock_contract)],
    "frameos-home-clock",
)
mutate_and_restore(
    bridge,
    b"return isKioskNode(mutation.target) || Array.from(mutation.addedNodes).some(isKioskNode) || Array.from(mutation.removedNodes).some(isKioskNode);",
    b"return false;",
    ["node", str(bridge_vm)],
    "frameos-bridge-mutation",
)

source_after = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_files}
test_after = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in test_files}
if source_after != source_before:
    raise AssertionError("source files were not restored after red checks")
if test_after != test_before:
    raise AssertionError("tests changed during proof")
(reports / "frameos-web-performance-proof.json").write_text(json.dumps({
    "passed": True,
    "sourceHashes": source_after,
    "testHashes": test_after,
    "checks": {
        "calendarProjectionCache": "red then restored green",
        "clockOnlyRender": "red then restored green",
        "bridgeReplacementPredicate": "red then restored green",
    },
}, indent=2) + "\n")
