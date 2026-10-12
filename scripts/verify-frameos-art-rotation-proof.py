#!/usr/bin/env python3
"""Prove Art persistence and bounded rotation state by exact source restoration."""
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
mutations = (
    (
        "persistence",
        "this.display(candidate.artwork, image); this.recordDisplayed(candidate.artwork.id); this.syncAutoplay();",
        "this.display(candidate.artwork, image); this.syncAutoplay();",
    ),
    (
        "history-bound",
        "if (this.history.length > MAX_HISTORY_ENTRIES) this.history.splice(0, this.history.length - MAX_HISTORY_ENTRIES);",
        "",
    ),
    (
        "collection-bound",
        "if (!title || input.artworks.length === 0 || input.artworks.length > MAX_ARTWORKS) return null;",
        "if (!title || input.artworks.length === 0) return null;",
    ),
    (
        "storage-bound",
        "typeof raw !== \"string\" || raw.length > MAX_ROTATION_STORAGE_BYTES",
        "typeof raw !== \"string\" || false",
    ),
    (
        "shown-id-bound",
        "Array.isArray(parsed.shownIds) || parsed.shownIds.length > MAX_ROTATION_SHOWN_IDS",
        "Array.isArray(parsed.shownIds)",
    ),
    (
        "serialized-id-bound",
        "!id || JSON.stringify(id).length > MAX_SERIALIZED_ID_CHARS || seen.has(id)",
        "!id || seen.has(id)",
    ),
)


def run(label: str) -> tuple[subprocess.CompletedProcess[str], str]:
    result = subprocess.run(["node", str(test)], cwd=root, capture_output=True, text=True)
    log = out / f"art-rotation-{label}.log"
    log.write_text(
        f"command: node {test}\nexit_code: {result.returncode}\n\nstdout:\n{result.stdout}\n\nstderr:\n{result.stderr}",
        encoding="utf-8",
    )
    return result, log.name


negative_runs: dict[str, dict[str, object]] = {}
for label, needle, replacement in mutations:
    if original.count(needle.encode()) != 1:
        raise SystemExit(f"expected exactly one {label} mutation marker")
    try:
        source.write_bytes(original.replace(needle.encode(), replacement.encode(), 1))
        red, log = run(f"{label}-source-red")
        if red.returncode == 0:
            raise SystemExit(f"Art rotation {label} mutation unexpectedly passed")
        negative_runs[label] = {"exit": red.returncode, "log": log}
    finally:
        source.write_bytes(original)
    if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
        raise SystemExit(f"Art source was not restored exactly after {label}")

green, green_log = run("restored-green")
if green.returncode != 0:
    raise SystemExit(green.stdout + green.stderr)
if hashlib.sha256(test.read_bytes()).hexdigest() != test_hash:
    raise SystemExit("Art rotation test changed during source proof")
(out / "art-rotation-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "successful display persistence plus bounded history, collection, escaped serialized IDs, and local-storage state",
    "command": ["node", str(test)],
    # Retain the original simple fields for existing receipt readers.
    "negative_exit": negative_runs["persistence"]["exit"],
    "restored_green_exit": green.returncode,
    "original_source_sha256": source_hash,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
    "negative_log": negative_runs["persistence"]["log"],
    "restored_log": green_log,
    "bounded_negative_runs": negative_runs,
}, indent=2) + "\n", encoding="utf-8")
print("Art rotation proof: six source mutations red and exact restored source green")
