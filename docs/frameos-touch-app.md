# FrameOS Touch

Wyatt requested a separate touchscreen app on October 12, 2026. The gesture-only
Lenovo application is a different product and remains available. FrameOS Touch
has its own application ID, `com.wyattfleming.touchframe`, entry point, navigation,
and lifecycle. It shares tested data, authentication, and rendering components,
not the Lenovo activity or its mode/gesture state machine.

## Interaction contract

- Home is a native, responsive dashboard. Its clock, navigation, and last known
  data render immediately; a network refresh never replaces it with a loading page.
- The navigation rail exposes Home, Photos, Art, Weather, Cameras, Calendar, and
  full Android Settings. Layouts target 1920×1080 and 1280×800 frames; actual
  readability and reachability still require device acceptance. Home remains
  reachable when companion settings reorder or disable other destinations.
- A configured Birds feed appears in Home and the rail. It shares the retained
  web surface, keeps navigation visible, and is absent when no feed is configured.
- Photos and Art fill the screen. Artwork stays uncropped with blurred margins.
  An inward swipe in the left 48dp opens the menu; its scrim dismisses the menu.
- Android Settings opens directly and gets an explicit Return to FrameOS Touch
  overlay. Failure to establish that return route keeps the user in the app.
- Home, Calendar, climate controls, and Weather use the authenticated Home
  Assistant API. Home does not load an HA frontend in an invisible iframe.
- Calendar opens in a native Week view with seven dated columns, event times,
  titles, and up to three previews per day. Tap a day for its full agenda;
  busy-day details page in groups of twelve to bound view creation. Agenda and
  Month remain available with Today/period controls. It fetches real calendar
  events for the selected bounded range and marks partial/offline data; local
  date selection and detail paging do not fetch the same range again. Task lists
  are not part of this calendar integration.
- Reuse the existing Kiosk photo source and art feed, including paid-work
  exclusions, older-album coverage, no-repeat artwork cycle, and 120-second art
  interval. Preserve the companion and bounded offline behaviors during integration.

## Data and lifecycle

Home's API requests address a small configured set of entities, not `/api/states`.
Calendar windows, result counts, HTTP bytes, deadlines, and concurrency are bounded.
Refresh work belongs to a lifecycle generation; old responses cannot update a new
screen or credentials. UI navigation does not wait for a network response.

Authentication uses the existing same-origin OAuth verifier, encrypted Android
Keystore store, bounded HTTPS transport, and short-lived embedded sign-in view.
The separate package must sign in independently; never copy another application's
encrypted tokens or Keystore material. Provision only reviewed frame configuration
on-device. Do not log URLs containing secrets, tokens, or personal response bodies.

Media has a single foreground rendering owner. Native Home avoids the previous
Home/Photos GeckoView ownership conflict. Do not remove the Lenovo app's protective
detachment pin or infer that retained sessions imply free background rendering.

## Evidence and release gates

The B7 recovery APK remains installed on the large QA frame. Three Home/Photos
round trips completed; observed Home upper bounds were 17.474–18.482 seconds and
Photos 9.141–10.955 seconds. Every warm Home return missed the 15-second target.
Those observations motivate this architecture; they do not prove a network reload.

The new application is initially a source candidate. Required gates are guarded
NAS or Windows Linux-worker
compilation/lint/JVM tests, source-only negative/restored-green proofs, exact APK
and source identity, and large-frame UI acceptance. Test responsive layouts,
one-tap navigation, cached/offline states, Settings return, real touch edge menu,
full-image artwork, and repeated transitions with aggregate app/Gecko memory.
Measure tap-to-first-usable-content separately from background data freshness.

API references: [Home Assistant REST API](https://developers.home-assistant.io/docs/api/rest/)
and [authentication API](https://developers.home-assistant.io/docs/auth_api/).

## First-install boundary

The preview has no HOME intent filter or boot takeover enabled. Install it beside
B7 only after the build gates pass, keeping Frameo's default HOME unchanged. Initial
configuration uses the `com.wyattfleming.touchframe.PROVISION` broadcast to
`.control.TouchProvisionReceiver`, protected by Android's DUMP permission. It accepts
bounded non-secret configuration and durably commits it on a worker; it refuses
any existing configuration. A success returns `provisioned-restart-required`.
Provision before the first Activity launch. It never copies OAuth or companion
credentials from another package.

An optional `weather_location_label` string identifies the city or place in the
Weather header. It is display text, not a change to the configured weather entity
or forecast coordinates. Initial provisioning accepts up to 256 input characters;
configuration collapses whitespace and retains at most 80, and the header displays
up to 28 with an ellipsis. Existing frames retain their configuration when the app
is updated. The one-time provisioning receiver deliberately does not overwrite an
existing configuration; an authenticated configuration migration must preserve
all other fields and OAuth state. Weather typography is approximately 20% larger
than the original shared Weather renderer.

The source integrates a 12-photo/24-MiB private offline reserve, uncropped fallback
with a small blurred backdrop, monotonic remote settings, quiet-hours brightness,
idle return, and bounded companion callbacks. Companion polling remains disabled
until a reviewed takeover provides this package's own credentials and enables it;
never run old and new pollers with the same device identity at once. Events use
durable at-most-once claims; a lifecycle interruption after a claim may suppress
that event rather than showing it twice. Photos pause/hold is session state.

Camera mode currently provides authenticated snapshots, not live video. Birds
runtime verification, boot recovery, companion provisioning/takeover and physical UI acceptance remain
open parity gates before replacing the old app. API37 local-network permission
handling is source-only until a compatible runtime test is available.

No new Touch APK has been built, signed, installed, or accepted yet. The earlier
performance-remediation branches are retained; source changes are not deployment
claims. Shared backend and curation fixes remain in scope for this new application.
