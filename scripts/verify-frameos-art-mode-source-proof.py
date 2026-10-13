#!/usr/bin/env python3
"""Cheap source-only red/green proof for Art's isolated rendering contract."""
from __future__ import annotations

import sys
from pathlib import Path


def violations(root: Path, router: str, surface: str, configuration: str, availability: str) -> list[str]:
    errors: list[str] = []
    required = {
        "router": (
            "FrameMode.ART -> artUrl?.let { url -> FrameSurfaceTarget.Web(FrameWebSlot.ART, url) }",
            "FrameSurfaceTarget.Unavailable(FrameMode.ART)",
        ),
        "surface": (
            "FrameWebSlot.ART -> artSession ?: ManagedSession(",
            "label = \"Art\"",
            "configuredUrls = listOf(requireNotNull(artUrl))",
            "private fun disposeArtSession()",
        ),
        "configuration": ("val artUrl: String? = null", "val normalizedArtUrl = artUrl?.trim()?.ifBlank { null }"),
        "availability": ("FrameMode.ART || !artUrl.isNullOrBlank()", "artConfigured = !artUrl.isNullOrBlank()"),
    }
    for label, values in required.items():
        source = {"router": router, "surface": surface, "configuration": configuration, "availability": availability}[label]
        for value in values:
            if value not in source:
                errors.append(f"{label} missing {value!r}")
    forbidden = (
        "FrameWebSlot.ART -> photosSession",
        "FrameWebSlot.ART -> FramePhoto",
        "FrameMode.ART -> FrameSurfaceTarget.Web(FrameWebSlot.PHOTOS",
    )
    for value in forbidden:
        if value in surface or value in router:
            errors.append(f"Art is incorrectly coupled to family Photos: {value!r}")
    return errors


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) == 2 else ".").resolve()
    kotlin = root / "frameos/app/src/main/kotlin/com/wyattfleming/frameos"
    router_path = kotlin / "web/FrameSurfaceRouter.kt"
    surface_path = kotlin / "web/FrameWebSurface.kt"
    configuration_path = kotlin / "config/FrameConfiguration.kt"
    availability_path = kotlin / "navigation/FrameModeAvailability.kt"
    router = router_path.read_text()
    surface = surface_path.read_text()
    configuration = configuration_path.read_text()
    availability = availability_path.read_text()
    green = violations(root, router, surface, configuration, availability)
    if green:
        raise SystemExit("green source check failed: " + "; ".join(green))

    # A source-only negative control: routing Art to Photos must violate the proof.
    red_router = router.replace(
        "FrameMode.ART -> artUrl?.let { url -> FrameSurfaceTarget.Web(FrameWebSlot.ART, url) }",
        "FrameMode.ART -> FrameSurfaceTarget.Web(FrameWebSlot.PHOTOS, photosUrl)",
        1,
    )
    red = violations(root, red_router, surface, configuration, availability)
    if not red:
        raise SystemExit("negative control unexpectedly passed")
    print("Art source proof: red control failed and restored source passed")


if __name__ == "__main__":
    main()
