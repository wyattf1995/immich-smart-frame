#!/usr/bin/env python3
"""Bounded, additive Skip/Screenshot backfill for explicit candidate rows.

The caller supplies JSONL through stdin: each row has only ``id`` and
``originalFileName``.  This avoids a full-library scan.  Default mode only plans;
``--apply`` adds Skip/Screenshot in batches of at most 500.  No descriptions,
EXIF, GPS, originals, VLM, or GPU work is touched.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from collections.abc import Iterable, Iterator, Mapping
from typing import Any, TextIO

try:
    from .policy import filename_skip_tag
except ImportError:
    from policy import filename_skip_tag

MAX_RESPONSE_BYTES = 1_048_576
MAX_CANDIDATES = 5_000
WRITE_BATCH_SIZE = 500
SKIP_TAG = "Skip/Screenshot"


class BackfillRequestError(RuntimeError):
    pass


def should_backfill_screenshot(original_file_name: str | None, existing_tag_names: Iterable[str]) -> bool:
    return filename_skip_tag(original_file_name) == SKIP_TAG and SKIP_TAG not in set(existing_tag_names)


def batches(values: Iterable[str], size: int = WRITE_BATCH_SIZE) -> Iterator[tuple[str, ...]]:
    group: list[str] = []
    for value in values:
        group.append(value)
        if len(group) == size:
            yield tuple(group)
            group = []
    if group:
        yield tuple(group)


def read_candidates(stream: TextIO, max_candidates: int) -> tuple[tuple[str, str], ...]:
    rows: list[tuple[str, str]] = []
    seen: set[str] = set()
    for raw in stream:
        try:
            row = json.loads(raw)
        except json.JSONDecodeError:
            raise BackfillRequestError("candidate input was invalid") from None
        if not isinstance(row, Mapping) or not isinstance(row.get("id"), str) or not isinstance(row.get("originalFileName"), str):
            raise BackfillRequestError("candidate input was invalid")
        asset_id = row["id"]
        if asset_id not in seen:
            seen.add(asset_id)
            rows.append((asset_id, row["originalFileName"]))
        if len(rows) > max_candidates:
            raise BackfillRequestError("candidate input exceeded the fixed limit")
    return tuple(rows)


def api_json(base_url: str, key: str, method: str, path: str, body: Mapping[str, Any] | None = None) -> Any:
    data = json.dumps(body, separators=(",", ":")).encode() if body is not None else None
    headers = {"x-api-key": key}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read(MAX_RESPONSE_BYTES + 1)
        if len(payload) > MAX_RESPONSE_BYTES:
            raise BackfillRequestError("Immich response exceeded the fixed limit")
        return json.loads(payload) if payload else None
    except BackfillRequestError:
        raise
    except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError):
        raise BackfillRequestError("Immich request failed") from None


def tag_names(asset: Mapping[str, Any]) -> tuple[str, ...]:
    tags = asset.get("tags")
    if not isinstance(tags, list):
        return ()
    return tuple(item.get("value") for item in tags if isinstance(item, Mapping) and isinstance(item.get("value"), str))


def find_skip_tag_id(tags: Any) -> str:
    if not isinstance(tags, list):
        raise BackfillRequestError("Skip/Screenshot tag response was invalid")
    for tag in tags:
        if isinstance(tag, Mapping) and tag.get("value") == SKIP_TAG and isinstance(tag.get("id"), str):
            return tag["id"]
    raise BackfillRequestError("Skip/Screenshot tag was unavailable")


def run(*, base_url: str, key: str, apply: bool, candidates: Iterable[tuple[str, str]]) -> dict[str, int]:
    counts = {"candidateRows": 0, "strongScreenshotNames": 0, "alreadyTagged": 0, "planned": 0, "applied": 0}
    selected: list[str] = []
    for asset_id, candidate_name in candidates:
        counts["candidateRows"] += 1
        if filename_skip_tag(candidate_name) != SKIP_TAG:
            continue
        # Fetch metadata as needed to avoid duplicate relationships and ensure the
        # SQL candidate filename matches the current Immich record before a write.
        asset = api_json(base_url, key, "GET", f"/api/assets/{asset_id}")
        if not isinstance(asset, Mapping) or asset.get("originalFileName") != candidate_name:
            raise BackfillRequestError("candidate metadata did not match")
        counts["strongScreenshotNames"] += 1
        if SKIP_TAG in tag_names(asset):
            counts["alreadyTagged"] += 1
            continue
        selected.append(asset_id)
        counts["planned"] += 1
    if apply and selected:
        tag_id = find_skip_tag_id(api_json(base_url, key, "PUT", "/api/tags", {"tags": [SKIP_TAG]}))
        for group in batches(selected):
            api_json(base_url, key, "PUT", "/api/tags/assets", {"tagIds": [tag_id], "assetIds": list(group)})
            counts["applied"] += len(group)
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description="Plan or add Skip/Screenshot tags from bounded JSONL candidates on stdin.")
    parser.add_argument("--ids-stdin", action="store_true", required=True, help="read candidate JSONL rows from stdin")
    parser.add_argument("--apply", action="store_true", help="perform additive tag writes; default is read-only planning")
    parser.add_argument("--max-candidates", type=int, default=MAX_CANDIDATES)
    args = parser.parse_args()
    if args.max_candidates < 1 or args.max_candidates > MAX_CANDIDATES:
        parser.error(f"--max-candidates must be between 1 and {MAX_CANDIDATES}")
    try:
        candidates = read_candidates(sys.stdin, args.max_candidates)
        key = os.environ["IMMICH_KEY"]
        result = run(base_url=os.environ.get("IMMICH_URL", "http://localhost:8080"), key=key, apply=args.apply, candidates=candidates)
    except (KeyError, BackfillRequestError):
        print("backfill failed safely", file=sys.stderr)
        return 1
    print(json.dumps({"mode": "apply" if args.apply else "plan", **result}, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
