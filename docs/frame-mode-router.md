# Gesture-driven FrameOS router

FrameOS turns the Lenovo frame into one full-screen, no-touch application. The
default remains the original five-view cycle. Provisioning a safe BirdNET-Go
dashboard URL enables a sixth disposable view:

```text
Photos <-> Home <-> Weather <-> [Birds] <-> Cameras <-> Calendar <-> Photos
```

FrameOS maps the observed OEM raw gesture scan codes directly. The
shell router remains the protected external-control and legacy-browser
fallback. FrameOS changes views without opening browser tabs or showing an
address bar:

- Photos and Home Assistant each retain one warm Gecko session.
- Home, Cameras, and Calendar share one Home Assistant iframe and change its
  route in place.
- Weather is native, caches the last forecast, and renders condition-specific
  scenes without a browser.
- Birds loads the separately hosted BirdNET-Go dashboard in a fresh Gecko
  session, including compact attributed species images in its recent and
  summary lists. It is never preloaded, and leaving the view closes that session.
- Leaving a legacy browser releases its background media processes; subsequent
  FrameOS-to-FrameOS transitions stay on the warm path.

The old Fully Kiosk plus Firefox deployment remains a supported rollback. With
the `FRAMEOS_*` settings blank, the same script uses the legacy four-view cycle
without Weather.

## Verification boundary

The protected receiver, direct `show` commands for the original five
destinations, the forward/reverse cycle, warm Home Assistant routing, native
Weather UI, and foreground recovery were exercised on a mounted Lenovo
CD-3L501F running stock Android 10. The same five-view contract is covered by
the repository tests and was also exercised on an Android emulator.

The optional Birds configuration, six-view ordering, URL policy, disposable
session lifecycle, and shell routing are covered by local unit and contract
tests. They have not been installed or visually verified on a physical Lenovo.

OEM gesture directions are still hardware input, not Android touch gestures.
They can change with frame rotation, firmware state, and the input device
selected in Key Mapper. Record and test one real gesture in each direction after
installing the router. Do not treat scan codes 251 and 252 as universal
directions.

## Files

- [`frame-mode-router.sh`](../examples/frame-mode-router/frame-mode-router.sh)
  contains the five-view state machine, optional Birds insertion, and protected
  launch sequence.
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
./gradlew testDebugUnitTest lintRelease assembleRelease
# Sign app/build/outputs/apk/release/app-release-unsigned.apk with the
# deployment keystore kept outside this repository.
adb -s DEVICE_SERIAL install -r /path/to/signed-frameos-release.apk
```

Use the debug APK only for emulator or temporary development work. The
permanent wall display should run a signed release build: release rejects the
debug-only activity extras, while the `android.permission.DUMP`-protected
receiver remains available to the trusted shell router. Installing a newer APK
with `-r` and the same signing certificate preserves configuration and encrypted
sessions.

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
  --es frameos.birds_url 'http://UNRAID-LAN-HOST:8091/' \
  --es frameos.weather_entity_id 'weather.forecast_home'
adb -s DEVICE_SERIAL shell am start --activity-reorder-to-front \
  -n com.wyattfleming.frameos/.MainActivity
```

Do not put a password, access token, camera URL, or other credential in these
commands. Home Assistant authentication occurs in its own browser session, and
native Weather stores the resulting OAuth session with Android Keystore.
Omit `frameos.birds_url` until the BirdNET-Go frame view is reachable. Legacy
stored configurations remain valid and omit Birds from the cycle. FrameOS
rejects a Birds URL containing user information or other forbidden URL data.

## Install the router

Prepare a private config from the example. Set all three `FRAMEOS_*` values:

```sh
FRAMEOS_PACKAGE='com.wyattfleming.frameos'
FRAMEOS_ACTIVITY='com.wyattfleming.frameos/.MainActivity'
FRAMEOS_RECEIVER='com.wyattfleming.frameos/.control.FrameControlReceiver'
FRAMEOS_BIRDS_ENABLED='1'
```

Enable the router flag only after FrameOS has accepted `frameos.birds_url`.
Leaving it at the default `0` preserves the existing five-view sequence, and a
direct `show birds` fails without changing the saved mode.

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
  show birds /data/local/tmp/frame-mode-router.conf.new
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
rejected by an atomic owner-record symbolic link containing the PID,
process-start token, and lease timestamp. A symbolic link is used because the
tested Android 10 build denies hard-link creation in `/data/local/tmp`. The
router reclaims only a lock whose recorded owner is gone (or whose PID has been
reused with a different `/proc` start token) *and* whose
`LOCK_LEASE_SECONDS` has elapsed. Reclamation first atomically renames the stale
link, so it cannot delete a replacement lock acquired by another invocation.
It never steals an old lock held by the same live process. Keep the default
90-second lease above Key Mapper's 30-second command timeout.

Direct `show MODE` commands do not first inspect the foreground task: they
send the requested FrameOS command immediately. Legacy Firefox `show` commands
make one immediate resumed-activity check, then at most three configurable
one-second checks (`FIREFOX_READY_MAX_PROBES=4` and
`FIREFOX_READY_POLL_SECONDS=1` by default). The toolbar-collapse swipe runs
only after Firefox is actually resumed; a bounded timeout preserves the selected
mode without swiping an unrelated foreground surface.

## Contextual no-touch controls

Mode gestures and within-view controls remain separate:

| Physical input | FrameOS action |
| --- | --- |
| Forward gesture | Next configured view |
| Reverse gesture | Previous configured view |
| Volume Down | Next photo, next Weather page, or `Tab` in a web view |
| Volume Up | Previous photo, previous Weather page, or `Shift+Tab` in a web view |
| Star | Primary action or `Enter` in a web view |
| Long raw Star, when not converted by Key Mapper | Return directly to Home |

The native Weather view automatically rotates through all 24 forecast hours;
the volume buttons move those pages manually. Calendar focus can reach its
Today, previous/next, and view-mode controls. The same Tab/Enter behavior works
for future focusable controls added to the Home and Cameras dashboards.
Birds uses the same Tab/Shift+Tab/Enter forwarding without retaining its web
session after another mode is selected.
The supplied Key Mapper profile converts Star to Enter, so its normal physical
deployment does not claim a separate long-press shortcut.

FrameOS coalesces a rapid gesture burst before it renders an expensive Gecko
view. Each intentional gesture advances the pending destination and updates
the mode HUD immediately; only the final destination renders after a 240 ms
settle window. A burst cannot postpone rendering beyond 900 ms. Key repeats and
input delivered more than 500 ms late are consumed without replaying stale
navigation.

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

Those shell actions are a fallback for legacy routing. Once physical testing
proves FrameOS receives both raw gestures directly, leave the two Key Mapper
gesture-to-shell rules disabled. Running both paths for one gesture queues a
second router command several seconds later, which can replay old mode changes
and make navigation appear to bounce. Keep unrelated Key Mapper recovery and
button rules intact.

## Reboot and recovery

FrameOS declares a private receiver for completed boot and replacement of its
own package. It tries to foreground `MainActivity` immediately, then schedules
at most two inexact wake-up retries around 15 and 60 seconds. A successful
`onResume` cancels the remaining alarms. This is a bounded recovery window, not
a permanent foreground service, and it adds no ongoing notification.

Android 10 restricts starting an activity from the background. Grant
**Display over other apps** to FrameOS once, and explicitly allow FrameOS in
the firmware's hidden **DuraSpeed** application list. These settings are
per-package; an existing grant for Fully does not cover FrameOS. With trusted
USB ADB attached, the special-access grant can be applied and verified without
navigating the no-touch Settings UI:

```sh
adb -s DEVICE_SERIAL shell appops set \
  com.wyattfleming.frameos SYSTEM_ALERT_WINDOW allow
adb -s DEVICE_SERIAL shell appops get \
  com.wyattfleming.frameos SYSTEM_ALERT_WINDOW
```

Keep USB attached for the first controlled reboot. Verify FrameOS becomes the
resumed activity without an explicit `am start`, then test both physical
gesture directions and a rapid multi-gesture burst. Do not call automatic boot
recovery verified merely because the receiver is installed or its unit tests
pass.

Key Mapper's accessibility setting and rules persist, but its Expert Mode ADB
sysbridge did not auto-start after an ordinary reboot on the tested Android 10
firmware. With trusted ADB attached, open Key Mapper's Expert Mode page and run
the exact command it displays. For Key Mapper 4.3.1 FOSS it was:

```sh
adb -s DEVICE_SERIAL shell sh \
  /data/user_de/0/io.github.sds100.keymapper/start.sh
```

Verify that Key Mapper reports `Running`, its accessibility service is bound,
and the gesture input device exists when legacy shell mappings are needed.
FrameOS direct raw gestures do not require that sysbridge. Wireless ADB is
privileged shell access and did not survive reboot on the locked tested
firmware; automatic display recovery does not make remote shell maintenance
persistent, and wireless ADB must not be exposed to a general-purpose LAN.

An interrupted command normally self-recovers after its lease. A malformed lock
record intentionally does not auto-delete because ownership cannot be proven.
First confirm that no router command is running, then remove only the configured
lock *file* through trusted ADB. Do not remove a lock merely because it is old:
a slow, live transition remains protected.

For rollback, disable the two gesture mappings and restore the verified Key
Mapper export plus the known-good router/config pair. The legacy config keeps
all `FRAMEOS_*` settings blank and resumes Fully for Photos and Firefox for
Home Assistant. Do not clear Fully, Firefox, FrameOS, or Key Mapper app data as
routine troubleshooting; that removes useful sessions and recovery state.
