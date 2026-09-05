#!/usr/bin/env python3
"""Bounded, additive Skip/Screenshot backfill for already-tagged Takeout assets.

Default mode is read-only planning.  ``--apply`` adds only the controlled
Skip/Screenshot tag; it never edits descriptions, EXIF, GPS, originals, or tags
other than that one.  It does not invoke the VLM or GPU.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from collections.abc import Iterable, Mapping
from typing import Any

try:  # package import for tests; direct import for deployment as a script
    from .policy import filename_skip_tag
except ImportError:
    from policy import filename_skip_tag

MAX_RESPONSE_BYTES = 1_048_576
SKIP_TAG = "Skip/Screenshot"


class BackfillRequestError(RuntimeError):
    pass


def should_backfill_screenshot(original_file_name: str | None, existing_tag_names: Iterable[str]) -> bool:
    return filename_skip_tag(original_file_name) == SKIP_TAG and SKIP_TAG not in set(existing_tag_names)


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


def run(*, base_url: str, key: str, apply: bool, max_assets: int) -> dict[str, int]:
    counts = {"scanned": 0, "screenshotNamed": 0, "alreadyTagged": 0, "planned": 0, "applied": 0}
    tag_id: str | None = None
    if apply:
        tag_id = find_skip_tag_id(api_json(base_url, key, "PUT", "/api/tags", {"tags": [SKIP_TAG]}))
    page = 1
    while counts["scanned"] < max_assets:
        result = api_json(base_url, key, "POST", "/api/search/metadata", {"page": page, "size": min(1000, max_assets - counts["scanned"]), "withExif": True, "type": "IMAGE"})
        assets = result.get("assets", {}).get("items", []) if isinstance(result, Mapping) else []
        if not isinstance(assets, list) or not assets:
            break
        for asset in assets:
            if not isinstance(asset, Mapping):
                continue
            counts["scanned"] += 1
            names = tag_names(asset)
            if filename_skip_tag(asset.get("originalFileName")) != SKIP_TAG:
                continue
            counts["screenshotNamed"] += 1
            if SKIP_TAG in names:
                counts["alreadyTagged"] += 1
                continue
            counts["planned"] += 1
            if apply:
                asset_id = asset.get("id")
                if not isinstance(asset_id, str):
                    raise BackfillRequestError("asset record was invalid")
                # One idempotent additive relationship write; no metadata/original endpoint.
                api_json(base_url, key, "PUT", "/api/tags/assets", {"tagIds": [tag_id], "assetIds": [asset_id]})
                counts["applied"] += 1
        page += 1
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description="Plan or add only Skip/Screenshot tags from strong original filenames.")
    parser.add_argument("--apply", action="store_true", help="perform additive tag writes; default is read-only planning")
    parser.add_argument("--max-assets", type=int, default=5000)
    args = parser.parse_args()
    if args.max_assets < 1 or args.max_assets > 5000:
        parser.error("--max-assets must be between 1 and 5000")
    try:
        key = os.environ["IMMICH_KEY"]
        result = run(base_url=os.environ.get("IMMICH_URL", "http://localhost:8080"), key=key, apply=args.apply, max_assets=args.max_assets)
    except (KeyError, BackfillRequestError):
        print("backfill failed safely", file=sys.stderr)
        return 1
    print(json.dumps({"mode": "apply" if args.apply else "plan", **result}, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
