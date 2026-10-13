#!/usr/bin/env python3
"""Prove Art persistence and bounded rotation state by exact source-only red/green runs."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

if not __debug__:
    raise RuntimeError("optimized Python is not permitted for proof validation")
if len(sys.argv) not in {1, 2}:
    raise SystemExit(f"usage: {Path(sys.argv[0]).name} [repository-root]")

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
        "gallery recreation retains the successful display and excludes it for this cycle",
    ),
    (
        "history-bound",
        "if (this.history.length > MAX_HISTORY_ENTRIES) this.history.splice(0, this.history.length - MAX_HISTORY_ENTRIES);",
        "",
        "rotation history retains only the bounded manual-navigation window",
    ),
    (
        "collection-bound",
        "if (!title || input.artworks.length === 0 || input.artworks.length > MAX_ARTWORKS) return null;",
        "if (!title || input.artworks.length === 0) return null;",
        "an oversized local collection is refused before it can grow rotation state",
    ),
    (
        "storage-bound",
        'typeof raw !== "string" || raw.length > MAX_ROTATION_STORAGE_BYTES',
        'typeof raw !== "string" || false',
        "oversized valid storage is ignored before JSON parsing",
    ),
    (
        "shown-id-bound",
        "Array.isArray(parsed.shownIds) || parsed.shownIds.length > MAX_ROTATION_SHOWN_IDS",
        "Array.isArray(parsed.shownIds)",
        "overlong shown-ID state is ignored without iterating it",
    ),
    (
        "serialized-id-bound",
        "!id || JSON.stringify(id).length > MAX_SERIALIZED_ID_CHARS || seen.has(id)",
        "!id || seen.has(id)",
        "an ID whose JSON representation exceeds the bound is refused",
    ),
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(label: str, expected_assertion: str | None) -> dict[str, object]:
    result = subprocess.run(["node", str(test)], cwd=root, capture_output=True, text=True)
    log = out / f"art-rotation-{label}.log"
    combined = result.stdout + result.stderr
    log.write_text(
        f"command: node {test}\nexit_code: {result.returncode}\n\nstdout:\n{result.stdout}\n\nstderr:\n{result.stderr}",
        encoding="utf-8",
    )
    assertion_messages = re.findall(r"AssertionError \[ERR_ASSERTION\]: (.+)", combined)
    if expected_assertion is None:
        if result.returncode != 0 or assertion_messages or "SyntaxError" in combined:
            raise RuntimeError(f"restored Art test was not green; see {log}")
    else:
        if result.returncode == 0:
            raise RuntimeError(f"Art rotation {label} mutation unexpectedly passed")
        if assertion_messages != [expected_assertion] or "SyntaxError" in combined:
            raise RuntimeError(
                f"Art rotation {label} failed for an unexpected reason: {assertion_messages}; see {log}"
            )
    return {
        "exit": result.returncode,
        "assertionFailures": assertion_messages,
        "log": log.name,
    }


if not source.is_file() or source.is_symlink() or not test.is_file() or test.is_symlink():
    raise RuntimeError("required regular Art source or Node test is missing")
negative_runs: dict[str, dict[str, object]] = {}
try:
    initial = run("initial-green", None)
    for label, needle, replacement, expected_assertion in mutations:
        if original.count(needle.encode()) != 1:
            raise RuntimeError(f"expected exactly one {label} mutation marker")
        try:
            source.write_bytes(original.replace(needle.encode(), replacement.encode(), 1))
            negative_runs[label] = run(f"{label}-source-red", expected_assertion)
        finally:
            source.write_bytes(original)
        if sha256(source) != source_hash:
            raise RuntimeError(f"Art source was not restored exactly after {label}")
    restored = run("restored-green", None)
    if sha256(test) != test_hash:
        raise RuntimeError("Art rotation test changed during source proof")
    receipt = {
        "proof": "frameos-art-rotation-v2",
        "passed": True,
        "command": ["node", str(test)],
        "initial": initial,
        "negativeRuns": negative_runs,
        "restored": restored,
        "originalSourceSha256": source_hash,
        "restoredSourceSha256": sha256(source),
        "unchangedTestSha256": test_hash,
        "scope": "Node assertion suite validates persistence and bounded local rotation state; browser/device rendering remains outside this proof",
    }
    (out / "art-rotation-source-proof.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
finally:
    source.write_bytes(original)
