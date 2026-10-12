#!/usr/bin/env python3
"""Prove the richer Home content with a source-only Node mutation."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
source = root / "examples/frameos/frameos-custom-home.js"
test = root / "scripts/tests/test-frameos-custom-home.mjs"
out = root / "reports/frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
original = source.read_bytes()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
needle = b'const forecastMarkup = forecast ?'
replacement = b'const forecastMarkup = false ?'
assert original.count(needle) == 1

def run(label):
    result = subprocess.run(["node", "scripts/tests/test-frameos-custom-home.mjs"], cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
    log = out / f"{label}.log"
    log.write_text(result.stdout)
    return result.returncode, log.name

try:
    source.write_bytes(original.replace(needle, replacement))
    red_exit, red_log = run("custom-home-content-source-red")
    assert red_exit != 0
    assert "ready forecast exposes a compact dated forecast row" in (out / red_log).read_text()
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original

green_exit, green_log = run("custom-home-content-restored-green")
assert green_exit == 0
assert hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
(out / "custom-home-content-source-proof.json").write_text(json.dumps({
    "passed": True,
    "negative_exit": red_exit,
    "negative_assertion_failures": 1,
    "restored_green_exit": green_exit,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
    "negative_log": red_log,
    "restored_log": green_log,
}, indent=2) + "\n")
print("custom Home content source-only red / exact restore green verified")
