#!/usr/bin/env python3
"""Fail the Touch build when its APK breaks a size budget (frameos/touchframe/performance-budget.json).

  verify-touchframe-size-budget.py <apk> <budget.json> <report.json>

Each breach names the metric, the budget, the measured value and the entries responsible. The report also lists
the ten largest entries, so a size change is visible in review whether or not it fails.
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path


def measure(apk: Path) -> dict:
    entries = [(info.filename, info.file_size) for info in zipfile.ZipFile(apk).infolist() if not info.is_dir()]
    native = [(name, size) for name, size in entries if name.startswith("lib/")]
    return {
        "total_bytes": apk.stat().st_size,
        "abis": sorted({name.split("/")[1] for name, _ in native if name.count("/") >= 2}),
        "native_bytes": sum(size for _, size in native),
        "dex_bytes": sum(size for name, size in entries if name.endswith(".dex") and "/" not in name),
        "gecko_omni_bytes": sum(size for name, size in entries if name == "assets/omni.ja"),
        "other_assets_bytes": sum(size for name, size in entries if name.startswith("assets/") and name != "assets/omni.ja"),
        "res_bytes": sum(size for name, size in entries if name.startswith("res/") or name == "resources.arsc"),
        "largest": sorted(entries, key=lambda item: -item[1])[:10],
        "entries": entries,
    }


def breaches(measured: dict, budget: dict) -> list[str]:
    found = []
    for metric in ("total_bytes", "native_bytes", "dex_bytes", "gecko_omni_bytes", "other_assets_bytes", "res_bytes"):
        limit = budget[metric]["max"]
        if measured[metric] > limit:
            found.append(f"{metric}: {measured[metric]:,} bytes > budget {limit:,} ({budget[metric]['why']})")
    extra = sorted(set(measured["abis"]) - set(budget["abis"]["allowed"]))
    if extra:
        found.append(f"abis: {extra} not in budget {budget['abis']['allowed']} ({budget['abis']['why']})")
    rule = budget["new_entry_bytes"]
    for name, size in measured["entries"]:
        if size > rule["max"] and name not in rule["known"]:
            found.append(f"new_entry_bytes: {name} is {size:,} bytes > budget {rule['max']:,} for an unlisted entry ({rule['why']})")
    return found


def main() -> int:
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    apk, budget_path, report = map(Path, sys.argv[1:])
    budget = json.loads(budget_path.read_text())["apk"]
    measured = measure(apk)
    found = breaches(measured, budget)
    report.write_text(json.dumps({
        "passed": not found, "apk": apk.name, "breaches": found,
        "measured": {key: value for key, value in measured.items() if key != "entries"},
        "budget": {key: value for key, value in budget.items() if key != "why"},
    }, indent=2) + "\n")
    for line in found:
        print(f"SIZE BUDGET BREACH {line}")
    print(f"touchframe size budget {'passed' if not found else 'FAILED'}: {measured['total_bytes']:,} bytes, "
          f"largest {measured['largest'][0][0]} {measured['largest'][0][1]:,}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
