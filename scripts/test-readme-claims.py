#!/usr/bin/env python3
"""Guard the README's public availability and Immich write-boundary claims."""

from pathlib import Path
import re
import unittest


README = Path(__file__).resolve().parents[1] / "README.md"


class ReadmeClaimsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = re.sub(r"\s+", " ", README.read_text())

    def test_read_only_promise_is_scoped_to_display_and_companion(self):
        intro = self.text.split("## Why this exists", 1)[0]
        self.assertIn("The default display and Frame remote", intro)
        self.assertIn("do not modify Immich originals, ratings, albums, tags, or sidecars", intro)
        self.assertIn("optional tagging pipeline", intro)
        self.assertIn("write-capable", intro)
        self.assertIn("`--apply`", intro)
        self.assertIn("tagging/RUNBOOK.md", intro)

    def test_status_separates_implemented_controls_from_future_work(self):
        status = self.text.split("## Project status", 1)[1].split("## License and upstream", 1)[0]
        for fact in ("Unreleased main", "bounded, in-process", "capture-burst",
                     "optional companion", "reversible per-frame", "hide",
                     "Restart-persistent", "visual-similarity", "future work"):
            with self.subTest(fact=fact):
                self.assertIn(fact, status)
        self.assertNotIn("future work includes persistent per-display", status)

    def test_old_tag_is_not_presented_as_containing_unreleased_features(self):
        self.assertIn("releases/tag/v0.1.0", self.text)
        self.assertIn("should not be assumed to describe that older tag", self.text)
        self.assertIn("tested on one Lenovo CD-3L501F running stock Android 10", self.text)


if __name__ == "__main__":
    unittest.main()
