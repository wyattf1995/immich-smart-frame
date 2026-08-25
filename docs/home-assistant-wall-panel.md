# Home Assistant wall panel

FrameOS is the preferred full-screen host for the frame's Home Assistant
dashboard. It embeds GeckoView, retains one authenticated Home Assistant
session, and routes Home, Cameras, and Calendar through one same-origin iframe.
That removes Firefox's address bar and avoids creating or reloading tabs during
normal view changes. The earlier Fully Kiosk plus Firefox arrangement remains
available as a rollback.

The companion dashboard is still separate from the slideshow container:
Immich Kiosk remains useful even when Home Assistant or the optional mappings
are unavailable.

The privacy-safe example reproduces one verified 1920x1080 deployment with
three views and an ambient background feature:

- a two-column Home view with a large current time/date, a lightweight agenda
  from the four calendar entities, and status cards;
- a four-camera 2x2 live grid;
- a full-screen monthly calendar;
- local, weather-state-driven background videos with fixed terrain and subtle
  motion.

See
[`dashboard.example.yaml`](../examples/home-assistant-wall-panel/dashboard.example.yaml),
[`build-weather-loops.sh`](../examples/home-assistant-wall-panel/build-weather-loops.sh),
and
[`keymapper-navigation.example.json`](../examples/home-assistant-wall-panel/keymapper-navigation.example.json).

## Verification boundary

The dashboard, four simultaneous camera feeds, calendar, animated backgrounds,
same-document Home Assistant routing, and direct `Tab`/`Shift+Tab`/`Enter`
navigation were verified on one Lenovo CD-3L501F running stock Android 10.
FrameOS direct routing and native Weather were also exercised on that mounted
frame and an Android emulator. The tested Home Assistant release was Core
2026.8.3.

Physical OEM gesture direction remains deployment-specific and is
**UNVERIFIED** after any mapping, orientation, or router change until a person
tests it on the mounted frame. The mappings are supplied for review and testing,
not as a claim that every firmware build or orientation emits the same input.

That reboot also stopped wireless ADB and Key Mapper's Expert Mode sysbridge.
Fully returned automatically, but gesture mappings did not reconnect until the
official Key Mapper `start.sh` command was run through trusted USB ADB. Reboot
only with that recovery path available; do not unlock or reset the frame for a
gesture fault.

## Why FrameOS embeds GeckoView

On the tested stock firmware, Fully Kiosk uses Android System WebView 74.
Nest's H.264 WebRTC sessions connected and received data there but decoded no
video frames. Gecko rendered four live feeds together, so FrameOS embeds
GeckoView for both the photo and Home Assistant surfaces while keeping their
sessions isolated.

Only one session is attached and active at a time. Photos stays warm for quick
return; Home Assistant stays authenticated and routes among Home, Cameras, and
Calendar without recreating its iframe. Media is suspended when a surface is
inactive, and leaving Cameras routes the shared Home Assistant surface away
from the live grid so decoder resources can settle. This is a model-specific
renderer result, not a general Fully Kiosk limitation.

## Home Assistant prerequisites

1. Create a dedicated, non-administrator Home Assistant user for the frame.
   Do not link it to a Person, and do not leave an owner session on a wall
   display.
2. Configure the weather, calendar, camera, and optional status integrations
   that you want to expose.
3. Install a compatible
   [`lovelace-animated-background`](https://github.com/rbogdanov/lovelace-animated-background)
   resource if you want the video backgrounds. The example does not bundle
   that third-party JavaScript.
4. Obtain Firefox, Fully Kiosk Browser, and Key Mapper from their publishers.
   This repository does not redistribute APKs.

Keep Home Assistant private behind a trusted LAN, VPN, or authenticated reverse
proxy. Never embed a token, password, public camera URL, or owner credential in
the dashboard. Review [Security](../SECURITY.md) and
[Device setup](device-setup.md#lock-down-the-frame-network) first.

## Deploy the FrameOS wrapper

Copy [`frameos-panel.html`](../examples/frameos/frameos-panel.html) and
[`frameos-oauth.html`](../examples/frameos/frameos-oauth.html) to Home
Assistant's `/config/www/` directory. The first becomes the warm dashboard
wrapper at `/local/frameos-panel.html`; the second returns native Weather's
OAuth result to the app.

The wrapper contains exactly one full-viewport iframe. Its fragments map to the
three dashboard routes:

| Wrapper fragment | Home Assistant route |
| --- | --- |
| `#home` | `/wall-panel/home?kiosk` |
| `#cameras` | `/wall-panel/cameras?kiosk` |
| `#calendar` | `/wall-panel/calendar?kiosk` |

When the Home Assistant shell is ready, changes use its history and
`location-changed` event instead of reloading the iframe. Login and recovery
pages intentionally fall back to one same-origin navigation. The wrapper adds
no external scripts, stores no credentials, and has no visible steady-state
chrome.

Readiness uses one initial light/shadow-DOM traversal, then scoped mutation
observers for newly added subtrees and shadow roots; it does not repeatedly
rescan the full dashboard. Home waits for its three primary headings (`Today`,
`Up next`, and `Home status`), Calendar waits
for its rendered calendar root, and Cameras waits for all four cards and
players, plus decoded video where Gecko exposes it. A slow camera route keeps
a small nonblocking “still connecting” message with card/player/decode counts
instead of declaring a single structural node ready or leaving a blank overlay.

Weather is deliberately native rather than another dashboard fragment. That
lets it retain the last successful forecast, show all 24 hourly entries in
automatic pages, and draw stable terrain with subtle condition-specific sun,
cloud, rain, snow, fog, wind, night-sky, and shooting-star animation. Animated
conditions redraw at a bounded 20 frames per second; static scenes sleep between
hourly-page changes.

## Prepare the weather media

The example builder expects four similarly composed source stills. See the
[weather input README](../examples/home-assistant-wall-panel/weather/README.md),
then run:

```sh
cd examples/home-assistant-wall-panel
./build-weather-loops.sh
```

Copy the generated MP4 files and your `neutral.png` to
`/config/www/wallpanel-weather/` on Home Assistant. Local media avoids an
external CDN dependency and unnecessary remote video transfer.

The builder fingerprints the four completed MP4 files together and rewrites
the MP4 URLs in `dashboard.example.yaml` with that SHA-256-derived `?v=` value.
Run the builder before copying the dashboard as well as the media. This is
required because Home Assistant may cache `/local/` media for 31 days: replacing
a file without its new versioned URL can otherwise keep the previous scene.

## Adapt the dashboard

Replace every generic entity below before importing the raw dashboard YAML:

| Example entity | Replace with |
| --- | --- |
| `weather.home` | Your forecast entity |
| `binary_sensor.internet_status` | An optional WAN/connectivity entity |
| `vacuum.robot_cleaner` | Your optional vacuum entity |
| `sensor.robot_battery` | Your optional vacuum battery entity |
| `sensor.robot_cleaning_progress` | Your optional cleaning-progress entity |
| `sensor.last_successful_backup` | Your backup-status entity |
| `camera.camera_1` through `camera.camera_4` | Four reliable camera entities |
| `calendar.family`, `calendar.personal`, `calendar.birthdays`, `calendar.holidays` | Calendars you intend to show |

The time/date entities must also exist. Home's **Up next** card reads only the
already-exposed `message`, `start_time`, `all_day`, and `location` state
attributes from each of the four calendar entities. It sorts one next item per
calendar and renders at most four items. Timed events show `Tue, Sep 15 · 6:00
PM`; all-day events show `Tue, Sep 15 · All day`, never a misleading midnight
time. Entries in another calendar year include the year (for example, `Tue,
Sep 15, 2027 · 6:00 PM`), while same-year entries stay compact. It does not
call a calendar service or fetch a separate event feed. Remove any optional
card whose entity your installation does not provide.

Create a UI-managed dashboard, open its raw configuration editor, and paste the
adapted `dashboard.example.yaml`. The built-in Calendar card defaults to Month;
its toolbar also offers Day and `List (7 days)`, the week-style list available
in the tested release.

The four live camera cards continuously consume streams while that view is
open. Avoid battery cameras or omit unreliable feeds. For Google Nest, current
WebRTC models provide live views and event entities but not Home Assistant's
server-side recording actions.

## Contextual physical-key navigation

The example Key Mapper export emits standard keyboard events. FrameOS assigns
them by active view:

| Physical input | Photos | Weather | Home / Cameras / Calendar |
| --- | --- | --- | --- |
| Volume Down | Next photo | Next hourly page | `Tab` |
| Volume Up | Previous photo | Previous hourly page | `Shift+Tab` |
| Star, observed as scan code 255 | Play/pause | Connect when needed | `Enter` |
| Long raw Star, without remapping | Home | Home | Home |

Back up the existing Key Mapper configuration, import the example with
**Append**, and review every rule before enabling it. On the tested calendar,
keyboard focus traversed Today, Previous, Next, Month, Day, then List (7 days).
The supplied profile maps Star to Enter and does not claim a separate physical
long-press action.

The star button may already have a recovery mapping used to approve Android's
USB-debugging prompt. A second global scan-code-255 rule can interact with that
action. Preserve the recovery path until a dialog-scoped replacement is proven,
and verify the combined physical behavior on the mounted device.

The tested frame also observed native gesture scan codes 249, 251, and 252.
Their direction relationship can change with orientation, so record gestures
on your own unit before assigning them. The optional
[frame mode router](frame-mode-router.md) publishes two disabled scan-code
examples and cycles Photos, Home, Weather, Cameras, and Calendar in either
direction.

## Rollback and recovery

- Export or back up Key Mapper before importing any mappings.
- Save the current raw Lovelace configuration before replacing it.
- Keep a USB-C OTG mouse as recovery input.
- Do not clear Fully, Firefox, FrameOS, or Key Mapper data as a routine fix.
- Wireless ADB does not survive reboot on the locked stock firmware. Rebooting
  can turn a browser problem into a new physical-access session.
- Key Mapper Expert Mode also needs its displayed `start.sh` command after a
  reboot on the tested Android 10 build.

The slideshow itself continues to use the main [Quick start](../README.md#quick-start),
[Curation](curation.md), and [Upgrade and rollback](upgrade-rollback.md) paths.
