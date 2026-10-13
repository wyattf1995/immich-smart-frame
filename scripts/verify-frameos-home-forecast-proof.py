#!/usr/bin/env python3
"""Run a reversible Node proof for the Home forecast refresh boundary."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else Path.cwd().resolve()
source = root / "examples/frameos/frameos-home-forecast.js"
test = root / "scripts/tests/test-frameos-home-forecast.mjs"
out = root / "reports/frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
command = ["node", str(test.relative_to(root))]
original = source.read_bytes()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()

def run(label: str) -> tuple[int, str, str]:
    log = out / f"home-forecast-{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
    return result.returncode, log.read_text(), str(log.relative_to(root))

baseline_exit, baseline_log, baseline_path = run("baseline-green")
if baseline_exit != 0:
    raise RuntimeError(baseline_log)
checks = (
    ("success-ttl", "const SUCCESS_TTL = 30 * 60 * 1000;", "const SUCCESS_TTL = 0;", "successful data is cached for thirty minutes"),
    ("reentrant-fence", "inFlight = request;\n      status = \"loading\"; notify();", "status = \"loading\"; notify();\n      inFlight = request;", "a later refresh remains fenced before it synchronously redraws"),
    ("iso-timestamp", "if (!/^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d/.test(value)) return null;", "if (!/^\\d{4}-\\d\\d-\\d\\d$/.test(value)) return null;", "Home Assistant ISO timestamps become a bounded local calendar day"),
    ("timeout-epoch", "if (timedOut) requestEpoch += 1;", "if (timedOut) requestEpoch += 0;", "a late success after timeout cannot overwrite unavailable state"),
)
results = {}
for label, before, after, expected in checks:
    try:
        text = original.decode("utf-8")
        if text.count(before) != 1:
            raise RuntimeError(f"expected one {label} guard")
        source.write_text(text.replace(before, after, 1), encoding="utf-8")
        red_exit, red_log, red_path = run(f"{label}-red")
        if red_exit == 0 or expected not in red_log:
            raise RuntimeError(f"{label} mutation did not fail its guarding test")
    finally:
        source.write_bytes(original)
        if source.read_bytes() != original:
            raise RuntimeError(f"exact {label} source restoration failed")
    green_exit, green_log, green_path = run(f"{label}-restored-green")
    if green_exit != 0:
        raise RuntimeError(green_log)
    results[label] = {"negative_exit": red_exit, "expected_failure": expected, "negative_log": red_path, "restored_green_exit": green_exit, "restored_log": green_path}
if hashlib.sha256(test.read_bytes()).hexdigest() != test_hash:
    raise RuntimeError("forecast test changed during proof")
(out / "home-forecast-source-proof.json").write_text(json.dumps({
    "passed": True,
    "command": command,
    "baseline_green_exit": baseline_exit,
    "baseline_log": baseline_path,
    "checks": results,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
}, indent=2) + "\n", encoding="utf-8")
print("Home forecast source-only red / exact restore green verified")
