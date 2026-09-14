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

FrameOS 0.2.9 (extension 1.0.6) and Frame TV 0.4.1 source are prepared. The FrameOS
extension's 30 JavaScript tests and bounded JSON-detail source-negative proof passed.
The NAS Android launcher additionally requires full JVM/lint/build checks and
source-only proofs for the group parser and native server-restart pause adoption.
Those Android checks and signing have not run yet. Actual frame/TV playback remains
**UNVERIFIED**; physical Lenovo QA requires Wyatt's explicit approval.
