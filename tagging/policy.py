"""Pure, fail-closed policy for the legacy Immich tagger.

This module plans additive API updates only.  It never receives an asset UUID as a
filename and never writes an original, EXIF field, or GPS value.
"""
from __future__ import annotations

import ast
import json
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

MAX_CONTENT_TAGS = 12
SKIP_PREFIX = "Skip/"
IMAGE_TYPES = frozenset({"photograph", "screenshot", "document", "meme", "graphic", "other"})
TYPE_SKIP_TAG = {
    "screenshot": "Skip/Screenshot",
    "document": "Skip/Document",
    "meme": "Skip/Meme",
    "graphic": "Skip/NonPhoto",
    "other": "Skip/NonPhoto",
}
# The word must be a complete leading or timestamp-prefixed filename component.
_DATE_TIME = r"\d{4}[-_]?\d{2}[-_]?\d{2}(?:[ _-]?\d{2}[-:.]?\d{2}(?:[-:.]?\d{2})?)?"
_SCREENSHOT_WORD = r"(?:screenshot|screen[ _-]?shot|screen[ _-]?capture)"
# Require both a screenshot word and a recognizable date/time component.  This
# avoids hiding ordinary photos named "Screenshot of …" or "… screenshot reference".
_SCREENSHOT_NAME = re.compile(
    rf"^(?:{_SCREENSHOT_WORD}[ _.-]+{_DATE_TIME}(?=$|[ _.-])|{_DATE_TIME}[ _.-]+{_SCREENSHOT_WORD}(?=$|[ _.-]))",
    re.IGNORECASE,
)


class ClassificationError(ValueError):
    """The VLM response is incomplete or unsuitable for a completion marker."""


class TaggerRequestError(RuntimeError):
    """A bounded remote request failed without exposing response or credentials."""


@dataclass(frozen=True)
class AssetPlan:
    desired_tag_names: tuple[str, ...]
    add_tag_names: tuple[str, ...]
    description_to_write: str | None
    complete: bool


def filename_skip_tag(original_file_name: str | None) -> str | None:
    """Return the deterministic screenshot tag for strong, standalone names only."""
    if not isinstance(original_file_name, str) or not original_file_name:
        return None
    name = original_file_name.rsplit("/", 1)[-1]
    return "Skip/Screenshot" if _SCREENSHOT_NAME.match(name) else None


def require_vlm_classification(value: Mapping[str, Any]) -> tuple[tuple[str, ...], str, str]:
    """Validate the mandatory small image type before any non-filename completion."""
    if not isinstance(value, Mapping):
        raise ClassificationError("VLM classification is missing")
    tags = value.get("tags")
    caption = value.get("caption")
    image_type = value.get("image_type")
    if not isinstance(tags, list) or not all(isinstance(tag, str) for tag in tags):
        raise ClassificationError("VLM tags are incomplete")
    if not isinstance(caption, str):
        raise ClassificationError("VLM caption is incomplete")
    if image_type not in IMAGE_TYPES:
        raise ClassificationError("VLM image type is missing or invalid")
    return tuple(tags), caption.strip(), image_type


def _legacy_keywords() -> Mapping[str, list[str]]:
    """Read the committed legacy vocabulary as data without executing its credential setup."""
    source = Path(__file__).with_name("legacy").joinpath("tagger.py").read_text()
    tree = ast.parse(source)
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == "KW" for target in node.targets):
            return ast.literal_eval(node.value)
    raise ClassificationError("legacy controlled vocabulary is unavailable")


_LEGACY_KEYWORDS = _legacy_keywords()
_LEGACY_PATTERNS = {
    tag: re.compile(r"\b(?:" + "|".join(re.escape(word.strip()) for word in words) + r")s?\b")
    for tag, words in _LEGACY_KEYWORDS.items()
    if words
}


def map_legacy_tags(free_tags: Iterable[str], caption: str) -> tuple[str, ...]:
    """Use the legacy controlled vocabulary, without path-derived or GPS inference."""
    haystack = " " + " ".join(free_tags).lower() + " || " + caption.lower() + " "
    return tuple(tag for tag, pattern in _LEGACY_PATTERNS.items() if pattern.search(haystack))


def _ordered_tags(tags: Iterable[str]) -> tuple[str, ...]:
    unique: list[str] = []
    seen: set[str] = set()
    for tag in tags:
        if tag and tag not in seen:
            seen.add(tag)
            unique.append(tag)
    skipped = [tag for tag in unique if tag.startswith(SKIP_PREFIX)]
    content = [tag for tag in unique if not tag.startswith(SKIP_PREFIX)]
    return tuple(skipped + content[: max(0, MAX_CONTENT_TAGS - len(skipped))])


def plan_asset_update(
    *,
    original_file_name: str | None,
    vlm: Mapping[str, Any] | None,
    existing_tag_names: Iterable[str],
    existing_description: str,
    mapped_content_tags: Iterable[str] | None = None,
) -> AssetPlan:
    """Return a fail-closed, additive tag/caption plan for one asset."""
    direct_skip = filename_skip_tag(original_file_name)
    if direct_skip:
        desired = _ordered_tags((direct_skip,))
        existing = set(existing_tag_names)
        return AssetPlan(desired, tuple(tag for tag in desired if tag not in existing), None, True)

    if vlm is None:
        raise ClassificationError("VLM classification is required")
    free_tags, caption, image_type = require_vlm_classification(vlm)
    tags = list(mapped_content_tags) if mapped_content_tags is not None else list(map_legacy_tags(free_tags, caption))
    if image_type in TYPE_SKIP_TAG:
        tags.append(TYPE_SKIP_TAG[image_type])
    desired = _ordered_tags(tags)
    if image_type == "photograph" and not desired and not caption:
        raise ClassificationError("VLM photograph had no usable tag or caption")
    existing = set(existing_tag_names)
    description = None
    if caption and (not existing_description or existing_description.startswith("[AI]")):
        description = f"[AI] {caption} [/AI]"
    return AssetPlan(desired, tuple(tag for tag in desired if tag not in existing), description, True)


def bounded_json_request(request: urllib.request.Request, *, timeout_seconds: int = 30, max_response_bytes: int = 1_048_576) -> Mapping[str, Any]:
    """Read a bounded JSON response and collapse transport details to a safe error class."""
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            payload = response.read(max_response_bytes + 1)
        if len(payload) > max_response_bytes:
            raise TaggerRequestError("remote response exceeded limit")
        decoded = json.loads(payload)
        if not isinstance(decoded, Mapping):
            raise TaggerRequestError("remote response is not an object")
        return decoded
    except TaggerRequestError:
        raise
    except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError):
        raise TaggerRequestError("remote request failed") from None
