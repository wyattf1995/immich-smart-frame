# Optional Home Assistant wall panel

The frame can keep Immich photos in Fully Kiosk Browser while Firefox displays
an optional Home Assistant dashboard. This companion setup is deliberately
separate from the slideshow container: Immich Kiosk remains useful even when
Home Assistant, Firefox, or the optional mappings are unavailable.

The privacy-safe example reproduces one verified 1920x1080 deployment with
three views and an ambient background feature:

- a two-column Home view with time, sunrise/sunset, status, and daily/hourly
  weather;
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

The dashboard, four simultaneous camera feeds in Firefox, calendar, animated
backgrounds, and direct browser `Tab`/`Shift+Tab`/`Enter` navigation were
verified on one Lenovo CD-3L501F running stock Android 10. The tested software
was Home Assistant Core 2026.8.3, Firefox 154, Fully Kiosk Browser 1.61.2, and
Key Mapper 4.3.1 FOSS.

Physical volume/star handling remains **UNVERIFIED** end to end. The mappings
are supplied for review and testing, not as a claim that every firmware build
or orientation emits the same input. On the tested unit, an ordinary full
reboot restored raw native-gesture events after Lenovo's OEM input path stopped
emitting them, and both gesture directions then activated the pre-router
destination mappings. The cyclic router mapping still needs a physical
post-import test.

That reboot also stopped wireless ADB and Key Mapper's Expert Mode sysbridge.
Fully returned automatically, but gesture mappings did not reconnect until the
official Key Mapper `start.sh` command was run through trusted USB ADB. Reboot
only with that recovery path available; do not unlock or reset the frame for a
gesture fault.

## Why two browsers

Fully Kiosk remains the photo browser because it provides a reliable fullscreen
slideshow. On the tested stock firmware, Fully uses Android System WebView 74.
Nest's H.264 WebRTC sessions connected and received data there but decoded no
video frames. Firefox's bundled GeckoView rendered four live feeds together,
so Firefox hosts Home Assistant while Fully hosts Immich.

This is a model-specific renderer result, not a general Fully Kiosk limitation.
Use one browser when it can render all of your content.

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

The time/date and next-sun-event entities must also exist. Remove any optional
card whose entity your installation does not provide.

Create a UI-managed dashboard, open its raw configuration editor, and paste the
adapted `dashboard.example.yaml`. The built-in Calendar card defaults to Month;
its toolbar also offers Day and `List (7 days)`, the week-style list available
in the tested release.

The four live camera cards continuously consume streams while that view is
open. Avoid battery cameras or omit unreliable feeds. For Google Nest, current
WebRTC models provide live views and event entities but not Home Assistant's
server-side recording actions.

## Optional physical-key navigation

The example Key Mapper export encodes these browser-wide actions:

| Physical input | Browser action |
| --- | --- |
| Volume Down | `Tab` |
| Volume Up | `Shift+Tab` |
| Star, observed as scan code 255 | `Enter` |

Back up the existing Key Mapper configuration, import the example with
**Append**, and review every rule before enabling it. On the tested calendar,
keyboard focus traversed Today, Previous, Next, Month, Day, then List (7 days).

The star button may already have a recovery mapping used to approve Android's
USB-debugging prompt. A second global scan-code-255 rule can interact with that
action. Preserve the recovery path until a dialog-scoped replacement is proven,
and treat the combined physical behavior as **UNVERIFIED** until tested.

The tested frame also observed native gesture scan codes 249, 251, and 252.
Their direction relationship can change with orientation, so record gestures
on your own unit before assigning them. The optional
[frame mode router](frame-mode-router.md) publishes two disabled scan-code
examples and cycles Photos, Home, Cameras, and Calendar in either direction.

## Rollback and recovery

- Export or back up Key Mapper before importing any mappings.
- Save the current raw Lovelace configuration before replacing it.
- Keep a USB-C OTG mouse as recovery input.
- Do not clear Fully, Firefox, WebView, or Key Mapper data as a routine fix.
- Wireless ADB does not survive reboot on the locked stock firmware. Rebooting
  can turn a browser problem into a new physical-access session.
- Key Mapper Expert Mode also needs its displayed `start.sh` command after a
  reboot on the tested Android 10 build.

The slideshow itself continues to use the main [Quick start](../README.md#quick-start),
[Curation](curation.md), and [Upgrade and rollback](upgrade-rollback.md) paths.
