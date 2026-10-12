#!/usr/bin/env python3
"""Run the Art gallery contract against one reversible source-only safety mutation."""
from __future__ import annotations

import hashlib
from pathlib import Path
import subprocess
import sys


root = Path(sys.argv[1] if len(sys.argv) == 2 else ".").resolve()
source = root / "examples/frameos-art/art.js"
test = root / "scripts/tests/test-frameos-art-gallery.mjs"
original = source.read_bytes()
source_hash = hashlib.sha256(original).hexdigest()
# The gallery has no native-mode source. Its equivalent forbidden behavior is an external image path.
needle = 'const IMAGE_PATH = /^images\\/[a-f0-9]{64}\\.jpg$/;'
replacement = 'const IMAGE_PATH = /^.+$/;'
if original.count(needle.encode()) != 1:
    raise SystemExit("expected exactly one untouched Art image path guard")
try:
    source.write_bytes(original.replace(needle.encode(), replacement.encode(), 1))
    red = subprocess.run(["node", str(test)], cwd=root, capture_output=True, text=True)
    if red.returncode == 0:
        raise SystemExit("negative Art path mutation unexpectedly passed")
finally:
    source.write_bytes(original)
if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
    raise SystemExit("Art source was not restored exactly")
green = subprocess.run(["node", str(test)], cwd=root, capture_output=True, text=True)
if green.returncode != 0:
    raise SystemExit(green.stdout + green.stderr)
print("Art gallery proof: external-path mutation red, restored source green")
