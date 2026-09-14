# Shared playback validation

Verified September 13, 2026. This evidence covers the built server and synthetic
browser clients. No live server, TV Photos URL, or physical Lenovo was changed.

## Server and browser

- Materialized source: `433ea1b5fb6e833c0548eef0baa9dba314b2d0d6`.
- Exact seven-patch reconstruction matches all 21 changed source files.
- NAS job: `frame-sync-server-browser-5-20260913-225238-a4b1665120ee`.
- Frontend recovery/cache/shared-player tests and TypeScript compilation passed.
- Full ordinary Go package suite passed. Coordinator race checks and shared-route /
  render-cancellation race checks passed. An earlier broad supplemental routes race
  run exceeded protected deadlines in existing large-image fixtures; neither those
  tests nor their production time budgets were changed.
- Two Chromium contexts with different viewport sizes and a 90-second clock skew
  displayed three matching decoded transitions, with matching image/history IDs.
  Largest measured transition difference was 0.401 ms on this one NAS host. This
  is synthetic browser timing, not a promise about physical devices or LAN latency.
- Late join, shared pause beyond the slide interval, simultaneous Next deduplication,
  Previous/Next from the other browser, offline hold, reconnect, and unchanged
  independent slideshow startup all passed. Both screenshots were visually reviewed.
- Four source-only red/green proofs passed without editing their guarding tests:
  coordinator command replay/session guards; same-origin control; browser timer
  receiver binding; displayed-slide metadata. Exact source bytes were restored.
- Fixture processes and browser contexts closed; NAS export/archive checksums were
  verified and the disposable workspace was retired.

Server binary SHA-256:
`6db5de83eef0592a958a735119572302ac5f49979cf96517af4cec9c7aecd1df`.

Local retained outputs:
`/Users/wyattfleming/.codex/private/frame-sync-20260913/artifacts/server-browser-5/`.
The validation ZIP contains commands, source identity, browser results/screenshots,
proof logs, and the source-negative proof receipt. The parent audit and exact patch
receipt are `server-validation-audit.json` and `patch-stack-verification.json` in
the containing evidence directory. Cold archive:
`/mnt/user/agent-archives/frame-sync-server-browser-5-20260913-225719-1f51ede80911`.

## Android and physical acceptance

FrameOS 0.2.9 (extension 1.0.6) and Frame TV 0.4.1 both compiled on the NAS.

- Android transport source: `61697ad1c6e4adba46fd7626a244e0c15ea8b9dd`, with all
  retained source files matching product commit `e21fe7a`.
- NAS job: `frame-sync-android-2-20260913-232408-d3193c3450f7`.
- FrameOS: 30 JavaScript tests, 253 JVM tests, debug/release lint, and release
  compilation passed. No skipped JVM tests or lint warnings/errors.
- Frame TV: 24 JVM tests, debug compilation, and lint passed. No skipped tests or
  lint errors; 18 warnings remain about existing vector sizing, drawing allocations,
  fixed TV orientation, resources, backup metadata, and tooling recommendations.
- Source-only group-parser and server-restart regression proofs failed with the
  relevant source change reverted, then passed after exact-byte restoration.
  TV URL-policy, grid-navigation, and weather source-only proofs also passed.
- The first run exposed an SDK enumeration incompatibility in the older TV lint.
  The corrected launcher uses the TV's admitted API 36 SDK separately from the
  frame's API 37 workspace. Every file in each installed API 37 platform payload
  matched the pinned official archive (11,384 files per candidate).
- Both unsigned APKs, the FrameOS R8 map, source/SDK identities, full test reports,
  and sanitized log were exported with verified hashes, archived, and the exact
  NAS workspace retired. See `android-validation-audit.json` in the evidence root.

Local Android outputs:
`/Users/wyattfleming/.codex/private/frame-sync-20260913/artifacts/android-nas-2/`.
Cold archive:
`/mnt/user/agent-archives/frame-sync-android-2-20260913-233037-f5e8c10cde81`.

Local signing is pending the shared Mac resource gate. Actual frame/TV playback
remains **UNVERIFIED**; physical Lenovo QA requires Wyatt's explicit approval.
