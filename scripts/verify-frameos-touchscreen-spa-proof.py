#!/usr/bin/env python3
"""Prove guarded touchscreen iframe SPA routing with a source-only mutation."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys


root = Path(sys.argv[1]).resolve()
source = root / "examples/frameos/frameos-touchscreen-panel.html"
test = root / "scripts/tests/test-frameos-touchscreen-panel.mjs"
out = root / "reports/frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
original = source.read_bytes()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
needle = b"if (trySpaNavigation(view)) return;"
replacement = b"if (false && trySpaNavigation(view)) return;"
assert original.count(needle) == 1


def run(label: str) -> tuple[int, str]:
    log = out / f"{label}.log"
    result = subprocess.run(
        ["node", "scripts/tests/test-frameos-touchscreen-panel.mjs"],
        cwd=root,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    log.write_text(result.stdout)
    return result.returncode, log.name


try:
    source.write_bytes(original.replace(needle, replacement))
    negative_exit, negative_log = run("touchscreen-spa-source-red")
    assert negative_exit != 0
    assert "authenticated same-origin HA shell reuses the iframe" in (out / negative_log).read_text()
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original

restored_exit, restored_log = run("touchscreen-spa-restored-green")
assert restored_exit == 0
assert hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
(out / "touchscreen-spa-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "guarded authenticated same-origin touchscreen iframe SPA navigation",
    "negative_exit": negative_exit,
    "negative_assertion_failures": 1,
    "negative_log": negative_log,
    "restored_green_exit": restored_exit,
    "restored_log": restored_log,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
}, indent=2) + "\n")
print("touchscreen SPA source-only red / exact restore green verified", flush=True)
