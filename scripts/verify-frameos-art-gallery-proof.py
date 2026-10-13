#!/usr/bin/env python3
"""Run the Art gallery contract against one reversible source-only safety mutation."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import sys


root = Path(sys.argv[1] if len(sys.argv) == 2 else ".").resolve()
source = root / "examples/frameos-art/art.js"
test = root / "scripts/tests/test-frameos-art-gallery.mjs"
out = root / "reports" / "frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
original = source.read_bytes()
source_hash = hashlib.sha256(original).hexdigest()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
# The gallery has no native-mode source. Its equivalent forbidden behavior is an external image path.
needle = 'const IMAGE_PATH = /^images\\/[a-f0-9]{64}\\.jpg$/;'
replacement = 'const IMAGE_PATH = /^.+$/;'
if original.count(needle.encode()) != 1:
    raise SystemExit("expected exactly one untouched Art image path guard")


def write_log(name: str, result: subprocess.CompletedProcess[str]) -> str:
    path = out / name
    path.write_text(
        f"command: node {test}\nexit_code: {result.returncode}\n\nstdout:\n{result.stdout}\n\nstderr:\n{result.stderr}",
        encoding="utf-8",
    )
    return path.name


red: subprocess.CompletedProcess[str] | None = None
green: subprocess.CompletedProcess[str] | None = None
red_log: str | None = None
green_log: str | None = None
try:
    source.write_bytes(original.replace(needle.encode(), replacement.encode(), 1))
    red = subprocess.run(["node", str(test)], cwd=root, capture_output=True, text=True)
    red_log = write_log("art-gallery-source-red.log", red)
    if red.returncode == 0:
        raise SystemExit("negative Art path mutation unexpectedly passed")
finally:
    source.write_bytes(original)
if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
    raise SystemExit("Art source was not restored exactly")
green = subprocess.run(["node", str(test)], cwd=root, capture_output=True, text=True)
green_log = write_log("art-gallery-restored-green.log", green)
if green.returncode != 0:
    raise SystemExit(green.stdout + green.stderr)
if hashlib.sha256(test.read_bytes()).hexdigest() != test_hash:
    raise SystemExit("Art gallery test changed during source proof")
(out / "art-gallery-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "Art gallery rejects external image paths",
    "command": ["node", str(test)],
    "negative_exit": red.returncode,
    "restored_green_exit": green.returncode,
    "original_source_sha256": source_hash,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
    "negative_log": red_log,
    "restored_log": green_log,
}, indent=2) + "\n", encoding="utf-8")
print("Art gallery proof: red evidence retained and restored source green")
