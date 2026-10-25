#!/usr/bin/env python3
"""Focused checks for the read-only TouchFrame mutation-target preflight."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts/verify-touchframe-mutation-targets.py"
SPEC = importlib.util.spec_from_file_location("mutation_targets", SCRIPT)
assert SPEC and SPEC.loader
mutation_targets = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mutation_targets
SPEC.loader.exec_module(mutation_targets)


class TouchFrameMutationTargetPreflightTest(unittest.TestCase):
    def test_current_runner_covers_direct_and_named_helper_targets(self) -> None:
        targets = mutation_targets.discover(REPOSITORY)
        mutation_targets.validate(targets, REPOSITORY)
        by_kind: dict[str, int] = {}
        for target in targets:
            by_kind[target.kind] = by_kind.get(target.kind, 0) + 1
        self.assertEqual(
            {"mutate": 48, "repository": 1, "bridge": 1, "activity": 1, "calendar": 2, "packaging": 1, "design": 106},
            by_kind,
        )

    def test_design_proof_mutations_are_checked_before_a_build(self) -> None:
        # Builds 19, 20, 22, 27, 28 and 38 died in the design proof's own preflight after the NAS queue and SDK
        # bootstrap; the same check now runs in seconds before anything is submitted.
        targets = [target for target in mutation_targets.discover(REPOSITORY) if target.kind == "design"]
        names = {target.name for target in targets}
        self.assertIn("tab-chip-padding-order-red", names)
        self.assertIn("camera-periodic-preempt-red", names)
        self.assertEqual(len(names), len(targets), "design mutation names must be unique")

    def test_rejects_an_empty_replacement_with_its_own_reason(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.kt"
            source.write_text("needle\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "replacement is empty"):
                mutation_targets.validate([mutation_targets.Target("empty", source, "needle", "", "fixture")], root)

    def test_rejects_replacement_already_in_correct_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.kt"
            source.write_text("needle\nreplacement\n", encoding="utf-8")
            target = mutation_targets.Target("ambiguous", source, "needle", "replacement", "fixture")
            with self.assertRaisesRegex(ValueError, "replacement is already present"):
                mutation_targets.validate([target], root)

    def test_rejects_missing_and_repeated_needles(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.kt"
            source.write_text("once\nonce\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "found 0"):
                mutation_targets.validate([mutation_targets.Target("missing", source, "absent", "replacement", "fixture")], root)
            with self.assertRaisesRegex(ValueError, "found 2"):
                mutation_targets.validate([mutation_targets.Target("repeated", source, "once", "replacement", "fixture")], root)


if __name__ == "__main__":
    unittest.main()
