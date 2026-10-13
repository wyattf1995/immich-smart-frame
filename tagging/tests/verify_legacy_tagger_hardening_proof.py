#!/usr/bin/env python3
"""Prove legacy tagger bounds with source-only mutations and exact restore."""
import hashlib
import json
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tagging" / "legacy" / "tagger.py"
TEST = ROOT / "tagging" / "tests" / "test_legacy_tagger_hardening.py"
OUT = ROOT / "reports" / "legacy-tagger-proof"
OUT.mkdir(parents=True, exist_ok=True)
COMMAND = ["python3", "-m", "unittest", "tagging.tests.test_legacy_tagger_hardening"]


def run(label):
    result = subprocess.run(COMMAND, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (OUT / f"{label}.log").write_text(result.stdout)
    return result


def proof(name, old, new, expected):
    original = SOURCE.read_bytes()
    text = original.decode()
    assert text.count(old) == 1, f"{name}: source needle drift"
    test_before = hashlib.sha256(TEST.read_bytes()).hexdigest()
    baseline = run(f"{name}-green-before")
    assert baseline.returncode == 0, f"{name}: baseline failed"
    try:
        SOURCE.write_text(text.replace(old, new))
        red = run(f"{name}-red")
        assert red.returncode != 0 and expected in red.stdout, f"{name}: expected guard failure missing"
    finally:
        SOURCE.write_bytes(original)
    assert SOURCE.read_bytes() == original, f"{name}: source restore mismatch"
    restored = run(f"{name}-green-after")
    assert restored.returncode == 0, f"{name}: restored test failed"
    test_after = hashlib.sha256(TEST.read_bytes()).hexdigest()
    assert test_before == test_after, f"{name}: test changed during proof"
    return {"name": name, "red_exit": red.returncode, "green_before_exit": baseline.returncode,
            "green_after_exit": restored.returncode, "test_sha256": test_before}


results = [
    proof("response-byte-limit", "if total > max_bytes:", "if False:", "AssertionError: ResponseTooLarge not raised"),
    proof("checkpoint-deduplication", "if asset_id:\n                        self.db.execute(\"INSERT OR IGNORE INTO processed(asset_id) VALUES (?)\", (asset_id,))", "if asset_id:\n                        self.db.execute(\"INSERT INTO processed(asset_id) VALUES (?)\", (asset_id,))", "sqlite3.IntegrityError"),
]
(OUT / "source-negative-proof.json").write_text(json.dumps({"status": "passed", "proofs": results}, indent=2))
print("legacy tagger source-only red / exact restore green verified")
