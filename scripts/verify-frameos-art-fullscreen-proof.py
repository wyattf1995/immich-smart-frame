#!/usr/bin/env python3
"""Prove Art keeps its complete foreground image before exact source restoration."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1] if len(sys.argv) == 2 else ".").resolve()
source = root / "examples/frameos-art/art.css"
test = root / "scripts/tests/test-frameos-art-gallery.mjs"
out = root / "reports" / "frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
original = source.read_bytes()
source_hash = hashlib.sha256(original).hexdigest()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
needle = "object-fit:contain"
replacement = "object-fit:cover"
if original.count(needle.encode()) != 1:
    raise SystemExit("expected one foreground contain rule")


def run(label: str) -> tuple[subprocess.CompletedProcess[str], str]:
    result = subprocess.run(["node", str(test)], cwd=root, capture_output=True, text=True)
    log = out / f"art-fullscreen-{label}.log"
    log.write_text(
        f"command: node {test}\nexit_code: {result.returncode}\n\nstdout:\n{result.stdout}\n\nstderr:\n{result.stderr}",
        encoding="utf-8",
    )
    return result, log.name


red: subprocess.CompletedProcess[str] | None = None
red_log: str | None = None
try:
    source.write_bytes(original.replace(needle.encode(), replacement.encode(), 1))
    red, red_log = run("source-red")
    if red.returncode == 0:
        raise SystemExit("cropping mutation unexpectedly passed")
finally:
    source.write_bytes(original)
if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
    raise SystemExit("Art stylesheet was not restored exactly")
green, green_log = run("restored-green")
if green.returncode != 0:
    raise SystemExit(green.stdout + green.stderr)
if hashlib.sha256(test.read_bytes()).hexdigest() != test_hash:
    raise SystemExit("Art fullscreen test changed during source proof")
(out / "art-fullscreen-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "Art foreground remains contain while local backdrop fills gaps",
    "command": ["node", str(test)],
    "negative_exit": red.returncode,
    "restored_green_exit": green.returncode,
    "original_source_sha256": source_hash,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
    "negative_log": red_log,
    "restored_log": green_log,
}, indent=2) + "\n", encoding="utf-8")
print("Art fullscreen proof: crop mutation red and exact restored source green")
