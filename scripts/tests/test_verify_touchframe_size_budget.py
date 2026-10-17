#!/usr/bin/env python3
"""The size gate must pass the committed budget's own shape and fail each kind of regression it exists to catch."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("size_budget", ROOT / "scripts/verify-touchframe-size-budget.py")
size_budget = importlib.util.module_from_spec(spec)
spec.loader.exec_module(size_budget)
BUDGET = json.loads((ROOT / "frameos/touchframe/performance-budget.json").read_text())["apk"]


def apk(directory: Path, entries: dict[str, int]) -> Path:
    path = directory / "fixture.apk"
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, size in entries.items():
            archive.writestr(name, b"\0" * size)
    return path


BASE = {"lib/armeabi-v7a/libxul.so": 2_000_000, "assets/omni.ja": 1_500_000, "classes.dex": 1_200_000,
        "assets/fonts/display.ttf": 90_000, "res/layout/a.xml": 2_000, "AndroidManifest.xml": 3_000}


class SizeBudgetTest(unittest.TestCase):
    def check(self, entries: dict[str, int]) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            return size_budget.breaches(size_budget.measure(apk(Path(tmp), entries)), BUDGET)

    def test_an_apk_shaped_like_today_passes(self) -> None:
        self.assertEqual([], self.check(BASE))

    def test_a_new_heavy_dependency_fails_by_name(self) -> None:
        found = self.check({**BASE, "lib/armeabi-v7a/libnewthing.so": 1_500_000})
        self.assertEqual(1, len(found))
        self.assertIn("new_entry_bytes: lib/armeabi-v7a/libnewthing.so", found[0])

    def test_a_second_abi_fails(self) -> None:
        self.assertTrue(any(line.startswith("abis: ['arm64-v8a']") for line in self.check({**BASE, "lib/arm64-v8a/libxul.so": 10})))

    def test_a_large_asset_fails_the_asset_budget(self) -> None:
        found = self.check({**BASE, "assets/photo.jpg": 900_000})
        self.assertTrue(any(line.startswith("other_assets_bytes: ") for line in found), found)

    def test_dex_growth_fails(self) -> None:
        found = self.check({**BASE, "classes.dex": 999_999, "classes2.dex": 2_100_000})
        self.assertTrue(any(line.startswith("dex_bytes: ") for line in found), found)


if __name__ == "__main__":
    unittest.main()
