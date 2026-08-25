# Gesture-driven FrameOS router

FrameOS turns the Lenovo frame into one full-screen, no-touch application with
five circular views:

```text
Photos <-> Home <-> Weather <-> Cameras <-> Calendar <-> Photos
```

The router translates the frame's two OEM gesture events into protected
FrameOS commands. FrameOS then changes views without opening browser tabs or
showing an address bar:

- Photos and Home Assistant each retain one warm Gecko session.
- Home, Cameras, and Calendar share one Home Assistant iframe and change its
  route in place.
- Weather is native, caches the last forecast, and renders condition-specific
  scenes without a browser.
- Leaving a legacy browser releases its background media processes; subsequent
  FrameOS-to-FrameOS transitions stay on the warm path.

The old Fully Kiosk plus Firefox deployment remains a supported rollback. With
the `FRAMEOS_*` settings blank, the same script uses the legacy four-view cycle
without Weather.

## Verification boundary

The protected receiver, direct `show` commands for all five destinations, the
forward/reverse cycle, warm Home Assistant routing, native Weather UI, and
foreground recovery were exercised on a mounted Lenovo CD-3L501F running stock
Android 10. The same five-view contract is covered by the repository tests and
was also exercised on an Android emulator.

OEM gesture directions are still hardware input, not Android touch gestures.
They can change with frame rotation, firmware state, and the input device
selected in Key Mapper. Record and test one real gesture in each direction after
installing the router. Do not treat scan codes 251 and 252 as universal
directions.

## Files

- [`frame-mode-router.sh`](../examples/frame-mode-router/frame-mode-router.sh)
  contains the five-view state machine and protected launch sequence.
- [`frame-mode-router.example.conf`](../examples/frame-mode-router/frame-mode-router.example.conf)
  keeps deployment values outside the reusable script.
- [`keymapper-mode-router.example.json`](../examples/frame-mode-router/keymapper-mode-router.example.json)
  contains two disabled Key Mapper gesture rules for review.
- [`frameos-panel.html`](../examples/frameos/frameos-panel.html) is the
  same-origin, single-iframe Home Assistant wrapper.
- [`frameos-oauth.html`](../examples/frameos/frameos-oauth.html) is the local
  OAuth return page used by native Weather.

## Back up first

Export **all** existing Key Mapper rules and move the ZIP off the frame. Verify
that it opens and contains `data.json`. Preserve any rule that approves the
Android USB-debugging dialog; it is an important recovery path on a no-touch
device.

Use **Append** for example imports. `Replace` deletes the live rule list and
should be used only for a deliberate restore from a verified full backup.

Also retain a known-good copy of the active router and config on the device.
The router can be staged under a `.new` name, exercised directly, and renamed
into place only after every view succeeds.

## Install and provision FrameOS

FrameOS requires JDK 17 and an Android SDK:

```sh
cd frameos
./gradlew testDebugUnitTest lintDebug assembleDebug
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Deploy the two local pages to Home Assistant's `/config/www/` directory so they
are available as `/local/frameos-panel.html` and `/local/frameos-oauth.html`.
Keep them same-origin with the configured Home Assistant URL.

Initial provisioning goes through a receiver protected by Android's
`android.permission.DUMP`; ordinary applications cannot send it. Run the
broadcast from an explicitly targeted trusted ADB shell, then foreground the
activity:

```sh
adb -s DEVICE_SERIAL shell am broadcast --user 0 \
  -a com.wyattfleming.frameos.CONTROL \
  -n com.wyattfleming.frameos/.control.FrameControlReceiver \
  --es frameos.photos_url 'http://FRAME-LAN-HOST:3000/' \
  --es frameos.home_assistant_url 'https://HOME-ASSISTANT-HOST/' \
  --es frameos.weather_entity_id 'weather.forecast_home'
adb -s DEVICE_SERIAL shell am start --activity-reorder-to-front \
  -n com.wyattfleming.frameos/.MainActivity
```

Do not put a password, access token, camera URL, or other credential in these
commands. Home Assistant authentication occurs in its own browser session, and
native Weather stores the resulting OAuth session with Android Keystore.

## Install the router

Prepare a private config from the example. Set all three `FRAMEOS_*` values:

```sh
FRAMEOS_PACKAGE='com.wyattfleming.frameos'
FRAMEOS_ACTIVITY='com.wyattfleming.frameos/.MainActivity'
FRAMEOS_RECEIVER='com.wyattfleming.frameos/.control.FrameControlReceiver'
```

The legacy Home, Cameras, and Calendar URLs remain syntactically required but
are not opened while FrameOS is enabled. They may remain safe
`https://*.invalid` placeholders in a FrameOS-only config. The config is
sourced as trusted shell input, so write it yourself and never install one from
an untrusted source.

Stage and verify both files over an explicitly targeted ADB connection:

```sh
adb -s DEVICE_SERIAL push examples/frame-mode-router/frame-mode-router.sh \
  /data/local/tmp/frame-mode-router.sh.new
adb -s DEVICE_SERIAL push /path/to/private-frame-mode-router.conf \
  /data/local/tmp/frame-mode-router.conf.new
adb -s DEVICE_SERIAL shell chmod 700 \
  /data/local/tmp/frame-mode-router.sh.new
adb -s DEVICE_SERIAL shell chmod 600 \
  /data/local/tmp/frame-mode-router.conf.new

adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh.new \
  show photos /data/local/tmp/frame-mode-router.conf.new
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh.new \
  show home /data/local/tmp/frame-mode-router.conf.new
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh.new \
  show weather /data/local/tmp/frame-mode-router.conf.new
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh.new \
  show cameras /data/local/tmp/frame-mode-router.conf.new
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh.new \
  show calendar /data/local/tmp/frame-mode-router.conf.new
```

After visual verification, atomically rename the staged files to
`/data/local/tmp/frame-mode-router.sh` and
`/data/local/tmp/frame-mode-router.conf`. Compare the deployed SHA-256 with the
local source. Keep the prior known-good router under a distinct backup name.

The router's state and lock paths are under `/data/local/tmp`. Missing or
malformed state safely defaults to Home. Rapid duplicate transitions are
rejected by an atomic lock directory.

## Contextual no-touch controls

Mode gestures and within-view controls remain separate:

| Physical input | FrameOS action |
| --- | --- |
| Forward gesture | Next view in the five-view cycle |
| Reverse gesture | Previous view in the five-view cycle |
| Volume Down | Next photo, next Weather page, or `Tab` in Home Assistant |
| Volume Up | Previous photo, previous Weather page, or `Shift+Tab` in Home Assistant |
| Star | Primary action or `Enter` in Home Assistant |
| Long raw Star, when not converted by Key Mapper | Return directly to Home |

The native Weather view automatically rotates through all 24 forecast hours;
the volume buttons move those pages manually. Calendar focus can reach its
Today, previous/next, and view-mode controls. The same Tab/Enter behavior works
for future focusable controls added to the Home and Cameras dashboards.
The supplied Key Mapper profile converts Star to Enter, so its normal physical
deployment does not claim a separate long-press shortcut.

## Record and map the gesture inputs

Keep trusted ADB attached while recording the actual frame events:

```sh
adb -s DEVICE_SERIAL shell getevent -lt
```

Import the Key Mapper example with **Append**, inspect both disabled rules, and
retarget or swap their triggers to the observed device and direction. Their
actions must remain:

```text
sh /data/local/tmp/frame-mode-router.sh next
sh /data/local/tmp/frame-mode-router.sh prev
```

Set `Execute with ADB` timeouts to at least 30,000 ms. Enable the rules only
after confirming that no existing global rule uses the same trigger.

## Reboot and recovery

Key Mapper's accessibility setting and rules persist, but its Expert Mode ADB
sysbridge did not auto-start after an ordinary reboot on the tested Android 10
firmware. With trusted ADB attached, open Key Mapper's Expert Mode page and run
the exact command it displays. For Key Mapper 4.3.1 FOSS it was:

```sh
adb -s DEVICE_SERIAL shell sh \
  /data/user_de/0/io.github.sds100.keymapper/start.sh
```

Verify that Key Mapper reports `Running`, its accessibility service is bound,
and the gesture input device exists. Do not reboot this no-touch frame without
a trusted USB recovery path. Wireless ADB is privileged shell access and did
not survive reboot on the locked tested firmware; do not expose it to a
general-purpose LAN.

If a command is interrupted and leaves the exact lock directory behind, first
confirm that no router command is running, then remove only the configured
lock directory.

For rollback, disable the two gesture mappings and restore the verified Key
Mapper export plus the known-good router/config pair. The legacy config keeps
all `FRAMEOS_*` settings blank and resumes Fully for Photos and Firefox for
Home Assistant. Do not clear Fully, Firefox, FrameOS, or Key Mapper app data as
routine troubleshooting; that removes useful sessions and recovery state.
