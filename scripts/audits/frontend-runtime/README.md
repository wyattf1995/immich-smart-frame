# Runtime audit harness

This is profiling instrumentation, not production behavior or a device acceptance test.
The product files are served unchanged from the source identified by the transport
manifest. All API responses, frame IDs and artwork are synthetic; requests to any
origin other than the loopback fixture are blocked.

Use the canonical guarded NAS browser runtime. It already contains Playwright and
Chromium at `/opt/launchpad-browser-runtime`; no dependency installation or external
network is needed. Export both `output/playwright/runtime-audit.tar.gz` and
`output/playwright/runtime-run.log`, including when a run fails. Preserve source
identity and the worker's export/archive/retirement receipts.

The four pages stay open while measurements run sequentially. Each baseline has
30 seconds of interactions between forced-GC heap snapshots. Additional cases
exercise 1,000 saved choices, a 20 Hz audio stream with oversized server responses,
Home state-wrapper churn, and more than two minutes of Art navigation. The harness
checks that the workload actually executed. It captures CDP timeline traces,
heap snapshots, screenshots, browser event timing, mutation counts and per-target
performance metrics. The total continuous-use interval must exceed five minutes.

Interpretation limits:

- CPU throttling is a relative slowdown on the NAS CPU, not an Android emulator.
- Art's generated SVG images exercise navigation/layout/blur, not JPEG decoding.
- The synthetic Home iframe is a data bridge, not the full Home Assistant frontend.
- Kiosk Photos and native Touch screens need separate runtime profiling.
- `elapsedMs` for an action includes Playwright transport and scrolling. Use browser
  event timing and CDP script/layout durations for main-thread claims instead.
- rAF gaps are scheduling observations, not a GPU/display dropped-frame count.
- Forced-GC heap deltas include instrumentation and caches. Growth is not by itself
  evidence of a leak. Inspect snapshots and navigation cleanup before attributing it.
- Traces include other open tabs; use per-target CDP metrics and identify the relevant
  process/thread before attributing individual trace events.
- Baseline/stress cases are different workloads, not a before/after optimization.
  No saved milliseconds may be claimed without a paired source intervention.

No app installation, credentials, real controls, firmware or household media are
needed or authorized by this harness.

## Source identity and optimized-view comparison

Every run requires `AUDIT-SOURCE-IDENTITY.json` in the source root. Its
`productCommit` and every listed non-harness product file are verified by byte
count and SHA-256 before the loopback server starts. A missing, malformed, or
mismatched receipt fails the run; the returned receipt is copied into the
archive.

The default invocation remains the historic baseline workload, including its
full saved-choice DOM and width-based Bird meter waits. Pass
`--optimized-views` only when validating the approved lazy Companion and
transform-based Bird implementations. It keeps the same 4 tabs, CPU ×4,
30-second baselines, 1,000-choice stress case, 20 Hz Bird stream, and
125-second Art interval. In this mode the harness additionally requires:

- a connected, closed Companion panel with zero saved-choice rows and zero
  preference GETs before the first expansion;
- exactly 50 visible rows after expansion of the 1,000-choice fixture, then a
  bounded second page; and
- a non-zero computed `transform`/`scaleX` meter instead of an inline width.

The profile records the available count, current page, and preference-request
counts in `companionOptimizedContract`. It does not claim a device, browser, or
NAS run until the guarded browser runtime exports its receipt.
