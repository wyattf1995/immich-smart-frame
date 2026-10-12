#!/usr/bin/env python3
"""Prove each richer Home content requirement through source-only Node negatives."""
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
cases = [
    ("nav", b"  const CLIMATE", b"  // fch-rail regression\n  const CLIMATE", "native rail is the only top-level navigation"),
    ("calendar", b"safe(eventLabel(event,", b"safe(eventTime(event.start,", "calendar rows render the date-aware event label"),
    ("forecast", b"const forecastMarkup = forecast ?", b"const forecastMarkup = false ?", "ready forecast exposes a compact dated forecast row"),
    ("forecast-unit", b"forecast, forecastUnit, forecastStatus", b"forecast, forecastStatus", "a forecast unit-only change repaints the compact forecast"),
    ("calendar-day", b"calendarDateKey:now.date, ", b"", "calendar labels refresh at the local day boundary"),
]

def run(label):
    result = subprocess.run(["node", "scripts/tests/test-frameos-custom-home.mjs"], cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
    log = out / f"custom-home-content-{label}.log"
    log.write_text(result.stdout)
    return result.returncode, log.name

negatives = []
try:
    for name, needle, replacement, assertion in cases:
        assert original.count(needle) == 1, name
        source.write_bytes(original.replace(needle, replacement))
        exit_code, log = run(f"{name}-red")
        assert exit_code != 0, name
        assert assertion in (out / log).read_text(), name
        negatives.append({"name": name, "exit": exit_code, "log": log, "assertion": assertion})
        source.write_bytes(original)
        assert source.read_bytes() == original
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original

green_exit, green_log = run("restored-green")
assert green_exit == 0
assert hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
(out / "custom-home-content-source-proof.json").write_text(json.dumps({
    "passed": True, "negatives": negatives, "negative_assertion_failures": len(negatives),
    "restored_green_exit": green_exit,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash, "restored_log": green_log,
}, indent=2) + "\n")
print("custom Home content source-only negatives / exact restore green verified")
