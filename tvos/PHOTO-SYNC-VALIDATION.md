# Shared playback validation

Initial build validation: September 13, 2026. Rollout/physical acceptance began
September 14 with explicit approval; see the dated rollout section below.

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

Local signing passed under the shared Mac resource gate. Both APKs have verified
alignment, package/version/min-SDK metadata, and v3 signatures matching the existing
device certificate. FrameOS extension 1.0.6 assets match the committed source.

Signed installation files:

- `artifacts/signed/frameos-0.2.9.apk` — SHA-256
  `a4a07c66d7ac5324e87d489ec03048a72ee6f070c9d25cbcea1fc82cfe1539d7`.
- `artifacts/signed/tvos-0.4.1.apk` — SHA-256
  `9421f85c3035958c69c2133fb0db6d339e96874566a64e56eeb8046371722e82`.

Paths above are relative to
`/Users/wyattfleming/.codex/private/frame-sync-20260913/`.
The signing receipt is `artifacts/signed/signed-artifacts.json`; the FrameOS R8 map
remains in the verified Android NAS output/archive above.

These initial artifacts preceded deployment. FrameOS 0.2.9 was superseded by0.2.10
for a narrow DUMP-protected group setting before any Lenovo installation.

## September 14 rollout

Evidence root: `/Users/wyattfleming/.codex/private/frame-sync-20260913/rollout-20260914`.
Server source 433ea1b is deployed in image
`sha256:47061ec8b33f7d09457ddbf6949f051f8cdf48556e8aa72479f1a79f953942f4`;
the staged runtime changed only the tested `/kiosk` binary. Existing filtering,
container limits and private settings were preserved; no live-host compilation.

Frame TV 0.4.1 and FrameOS 0.2.10 were installed with matching existing certificates,
verified device APK hashes, unchanged UIDs/first-install times and retained app data.
The group is `living-room`, balanced profile, 45 seconds. FrameOS 0.2.10 product source 9b33ad1 was validated as transport
`b303736019a38236300c215fdd92cbaf2779771a`: 257 FrameOS JVM tests, 24 TV JVM tests,
30 JS tests, no skipped JVM tests or lint errors. FrameOS lint had no warnings;
TV retained 18 warnings. Three new source-only configuration proofs passed.
Artifacts were exported/archived with verified hashes and the NAS workspace retired.

Physical0.2.10 acceptance verified matching screenshots, shared Companion pause /
Next / Previous, four matching automatically selected photos (three transitions),
and independent mode followed by rejoin without identity/configuration loss.
TV remoteNext while paused changed both actual images but left the frame's native
current-photo report stale; failure `qa-failed-1789395432.json` and paired screenshots
are retained. FrameOS 0.2.11 fixes the stale report by issuing a fresh native one-shot snapshot
only after an accepted paused shared settle. The exact guarding test failed when
that source block was removed and passed after restoring the identical source;
tests were unchanged during the proof. Product source:
`40cf414e0502e59e798b9a332e403ee8b636b3a0`; Android transport:
`2bcd14dfcfb75742fb30b48b11a34c9a33c1157e`.

Final build: 258 FrameOS JVM tests, 24 TV JVM tests, 30 JS tests; no JVM skips,
failures or errors. FrameOS lint remained warning/error-free; TV retained 18
warnings and no errors. All configuration, parser, reconnect, paused-settle and TV
source-only proofs passed. NAS job
`frame-shared-settle-android-20260914-072830-cb45905daf86` was exported, checksum
verified, archived and retired. Cold archive:
`/mnt/user/agent-archives/frame-shared-settle-android-20260914-073632-430cee7ca199`.
Local final build evidence: `settle-android-nas/` under the rollout evidence root.

Signed and installed FrameOS 0.2.11 SHA-256:
`38e04e1dca3828cc064ade8a8df7add643484c0246ea0b9773aaa7008a018b8a`.
The device APK, existing certificate, UID10104 and full first-install timestamp
were verified. HOME role, overlay grant, package grants and exact DuraSpeed
configuration were preserved. Frame TV remains the Sony HOME resolver; Plex,
Netflix, Prime Video, HBO and the Cast receiver remain enabled. This is package
state verification, not a fresh Cast session or streaming-provider login test.

On installed 0.2.11, TV-remote Next/Previous while paused updated both displayed
photos and the frame's native photo identity; the exact previously failing case
passed. A 55-second pause held the same photo on both screens. TV Back returned to
Frame TV Home; reopening Photos rejoined the same image. Resume propagated to both.
The final 0.2.11 automatic check also observed four matching photos (three
transitions). The original 0.2.10 failure and screenshots remain retained alongside the final
`final-*.json` acceptance receipts. Physical screenshot comparisons remain private.

Rollback instructions and exact original APK/config identities are in
`ROLLBACK.md` under the evidence root. Neither ROM reboot nor physical APK downgrade
was performed; long-term shutdown/restart resolution is outside these checks.
