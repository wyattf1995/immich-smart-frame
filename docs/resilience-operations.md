# Resilience operations

This runbook is intentionally read-only. It provides evidence for an operator
to decide whether to repair a dependency; it never restarts Kiosk, Immich, Home
Assistant, Unraid services, or the frame automatically.

## Backend request budgets and cancellation

The backend now derives request work from both the browser request context and
the process shutdown context. A browser navigation can stop foreground work
promptly, while process shutdown still stops any remaining request work even if
the client is still connected.

| Scope | Effective ceiling | Operator implication |
| --- | --- | --- |
| Foreground asset fetch/render | 30 seconds | Asset fetch, image decode, and related foreground work stop early on client cancel or the route budget. |
| General route and mutation handlers | 40 seconds | Mutations and route-side reconciliation are bounded even if a dependency stalls. |
| Configured `http_timeout` | Minimum of configured value and the route ceiling | Values above 40 seconds are clamped and logged instead of expanding foreground budgets indefinitely. |

Two low-cost observability rules follow from that model:

- `RequestCancellationObserver` is installed globally. It logs only when a
  request context ends before the handler returns.
- The emitted fields are limited to `method`, route template, `outcome`
  (`cancelled` or `timeout`), and `elapsed`. Raw URLs, query strings, device
  identifiers, and asset identifiers are intentionally excluded.

The image CPU path also checks the request context between bounded stages. In
practice that means a cancelled or timed-out request can stop between asset
processing, optimized resize, JPEG encoding, `convertImages` base64 conversion,
blur generation, and dominant-colour extraction instead of carrying all of that
CPU work to completion.

## Webhook dispatch model

Outbound webhooks are no longer tied to the foreground browser request. They
run on one process-wide dispatcher with a total admission cap of four deliveries
across all events and timeout values.

- Delivery is intentionally non-retrying. Rejections and downstream failures are
  logged; they are not replayed later.
- Every delivery fan-out shares one stable event identifier. The same value is
  present in the JSON payload `eventID`, the `X-Kiosk-Event-ID` header, and the
  optional HMAC signature input.
- Each admitted job applies its own delivery deadline, bounded by
  `http_timeout` and a hard 40-second ceiling, with a 20-second default when
  unset.
- Admission and rejection are logged with the event type, host, timeout, and
  event identifier so queue pressure is visible without logging raw endpoints.

On shutdown, `main` calls `DrainWebhookDispatch` concurrently with the other
drains before temporary media cleanup. The drain wait itself is bounded to five
seconds and logs a warning on timeout. A timed-out drain stops waiting; it does
not invent a retry or force a second delivery path.

## Video and live-photo background workers

Video download and live-photo promotion now use the same manager-bounded worker
pool instead of unbounded per-request goroutines. The manager admits at most
four background jobs at a time.

- Live-photo metadata lookup and the eventual download both run inside the
  admitted background task, so the foreground request can return promptly after
  scheduling while shutdown and timeout policy still apply.
- Each admitted job creates and owns its own timeout from the manager or
  shutdown context. Successful scheduling does not leak a timer back to the
  caller.
- Partial video files are guarded until commit. Any cancellation, timeout, or
  ordinary failure after file creation discards the file instead of leaving an
  orphaned temp file or an incomplete cache entry.
- Cache commit happens only after preview/blur work completes and the current
  generation is still valid.

Shutdown now waits for these workers through `DrainDownloads(ctx)` before the
temporary video directory is deleted. If the bounded wait expires, shutdown logs
the timeout and continues the normal process exit path.

## FrameOS weather, OAuth, and Gecko watchdog behavior

FrameOS already had route/stage/elapsed operation logging. The current Android
side keeps that model and adds deadline- and cancellation-aware behavior at the
weather, OAuth, and Gecko boundaries.

### Weather

- Home Assistant weather requests use the cancellable
  `UrlConnectionHomeAssistantTransport`, which accepts only absolute
  credential-free HTTPS URLs.
- Connect, read, and whole-request time budgets are clipped to the remaining
  request deadline, and a watchdog marks the request expired when that budget
  elapses.
- Current conditions, daily forecast, and hourly forecast run in parallel as one
  batch. Cancelling the batch cancels the futures and the in-flight HTTP
  transport.
- If the primary weather endpoint is offline and time remains, the app may try a
  configured fallback endpoint inside the same remaining budget.
- The repository keeps the last successful weather snapshot and can continue
  serving stale-but-usable data while a refresh is offline or requires
  reauthentication.

### OAuth

- OAuth code exchange and token refresh use the same bounded deadline model as
  weather requests and log `route`, `stage`, `outcome`, and `elapsedMs`.
- Pending OAuth state is stored synchronously because the external browser can
  outlive the app process. The callback consumes that state once so it cannot be
  replayed after a crash.
- OAuth sessions are stored through `AndroidKeystoreOAuthSessionStore`, which
  encrypts the session envelope in Android Keystore before writing it to shared
  preferences.
- `authEpoch` is stable for one login and survives access-token refreshes, so
  weather cache entries remain bound to the current authenticated session rather
  than to each refreshed token.

### Gecko

- `FrameWebSurface` arms a page-load watchdog on every top-level page start. A
  visible stalled load logs `route=<surface> stage=page_load outcome=timeout
  elapsedMs=...`, stops the load, and marks that surface unavailable.
- Automatic recovery retries run only for the currently visible surface. Hidden
  or lifecycle-suspended sessions do not keep retrying in the background.
- Photos and the persistent Home Assistant surface stay warm; the Cameras
  surface is disposable and is closed when the user leaves it so off-screen
  decoders and live streams are not retained.
- Lifecycle suspension stops active loads and clears retry callbacks. Returning
  to a visible surface resumes from the retained session when available or
  recreates it through the normal recovery path.

## Verification guidance

Use these checks when you need to confirm the resilience path without changing
production state:

- Cancel a foreground backend request from the client and confirm a single
  `request context ended` log with `method`, route template, `outcome=cancelled`,
  and `elapsed`.
- Lower `http_timeout` in a non-production environment until a slow route
  reaches the ceiling and confirm `outcome=timeout` plus the clamp warning when
  the configured value exceeds 40 seconds.
- Point one webhook at a controlled slow test sink and confirm admission,
  rejection, success, or timeout logs all reference the same event identifier
  that arrives in both the payload and `X-Kiosk-Event-ID`.
- During controlled shutdown, confirm that asset prefetch, webhook dispatch, and
  video download drains happen before temp-media cleanup. Warnings should appear
  only when the five-second drain budget expires.
- For video or live-photo refill validation, confirm that cancellation or an
  injected downstream failure does not leave a committed cache entry backed by a
  partial temp file.
- In FrameOS, watch `route=weather_*` and `route=oauth` logs for
  `outcome=cancelled`, `timeout`, `offline`, or `success`, and watch
  `route=<surface> stage=page_load` for Gecko watchdog timeouts and visible
  recovery.

## Intentional exclusions

This repository does not ship an application-owned database layer, LLM
integration, SSE endpoint, or WebSocket transport for the resilience paths
described above. Operators should not expect transaction rollback, stream
resumption, or connection-fanout drain behavior from this runbook.

Home Assistant may still use its own WebSocket internally inside the embedded
dashboard, but that connection is outside this app's code boundary and outside
the guarantees documented here.

## Kiosk liveness and dependency readiness

Compose uses Kiosk's `--livecheck` for Docker liveness. It confirms that the
local Kiosk process serves `/livez`. Kiosk's `--readycheck` probes its own
`/readyz` endpoint, which performs a bounded authenticated Immich dependency
read by requesting one random asset-selection result. That probe needs the
same `asset.read` permission as the slideshow and does not add the optional
`asset.statistics` permission. The two signals are deliberately separate:

- a failed live check means the Kiosk process needs operator attention;
- a failed ready check can mean Immich, the API key, or the network is down;
  restarting Kiosk blindly would add churn without repairing that dependency.

Run a one-time sample from the Docker host or schedule it with the host's
normal cron/monitoring mechanism:

```sh
./scripts/check-frame-readiness.sh
```

It exits nonzero on a failed requested check, so it can feed an existing alert
mechanism. It does not send alerts itself and does not claim an alerting service
is installed. With `--adb`, it also checks that FrameOS still has
`SYSTEM_ALERT_WINDOW`, that Lenovo DuraSpeed is either disabled globally or has
the exact `com.wyattfleming.frameos` package in its enabled whitelist, that the
app is running and resumed, and that input accessibility is not suppressed by a
leftover UI-automation session. All of those probes are reads; the sampler does
not grant an app-op, edit a setting, start an activity, or reboot the frame.

## Full-stack power or reboot audit

Before any planned power work, record a passing baseline and keep the verified
input snapshot and Android USB recovery cable available:

```sh
./scripts/check-frame-readiness.sh \
  --array-check-command 'YOUR_READ_ONLY_UNRAID_ARRAY_CHECK' \
  --ha-check-command 'YOUR_READ_ONLY_HOME_ASSISTANT_CHECK' \
  --adb DEVICE_SERIAL \
  --router-config /data/local/tmp/frame-mode-router.conf
```

The two optional command hooks are deliberately supplied by the local operator:
this public repository has no household endpoint, Unraid API contract, or Home
Assistant credential. Keep them read-only. For example, an array hook may check
the host's documented array-started state; an HA hook may make an authenticated
or LAN-local health request using a secret kept outside this repository.

After power returns and dependent services have settled, rerun the same command
and verify all requested checks. Then physically verify one gesture in each
direction and the three mapped buttons. The router check only reports shell
state; it cannot prove OEM hardware input.

## Known external limits

This repository cannot prove that a UPS exists, that Unraid BIOS settings restore
power automatically, that the array mounts, or that Android resumes wireless ADB
after a reboot. On the tested locked Android 10 frame, wireless ADB may require
the documented trusted-USB recovery path after an ordinary reboot. The sampler
reports Key Mapper's sysbridge as informational: direct raw-key FrameOS controls
remain the supported path when sysbridge is absent. Treat those as operator
prerequisites, not checks this script can manufacture.

The tested Lenovo firmware also declares its privileged stock launcher at HOME
intent priority 1. Android caps positive activity priorities from
non-privileged applications to 0, so the OEM launcher resolves first even when
FrameOS is both the saved preferred activity and the current HOME role holder.
The readiness sampler intentionally keeps reporting that mismatch instead of
turning it into a false pass. The protected router can still launch and recover
FrameOS explicitly, but automatic boot into FrameOS is not guaranteed on stock
firmware. Do not disable the complete OEM package merely to satisfy this check:
that package also owns frame-specific services whose removal has not been
validated. See Android's
[`<intent-filter>` priority rules](https://developer.android.com/guide/topics/manifest/intent-filter-element#priority).
