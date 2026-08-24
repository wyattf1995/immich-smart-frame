# Gesture-driven frame mode router

The optional router turns two repeatable hardware inputs into a circular set of
display modes:

```text
Photos <-> Home <-> Cameras <-> Calendar <-> Photos
```

Photos stays in Fully Kiosk Browser. The three Home Assistant views stay in a
single Firefox task, and `--activity-reorder-to-front` avoids creating a new
tab for every transition. The router records the current logical view in a
small state file so the same two inputs work as forward and backward controls.

This component is independent of the Immich Kiosk container. A router failure
does not stop the normal photo slideshow.

## Verification boundary

Direct `next`, `prev`, and `show` commands were exercised over ADB on one
Lenovo CD-3L501F running stock Android 10 with Firefox 154, Fully Kiosk Browser
1.61.2, and Key Mapper 4.3.1 FOSS. Repeated Android `VIEW` intents reused the
existing Firefox tab in that test. The small post-navigation content swipe
collapsed Firefox's toolbar on the Home, camera-grid, and fixed calendar
layouts.

The included scan-code rules are deliberately **disabled**. Lenovo gesture
directions can change with frame rotation, firmware state, and the input device
selected in Key Mapper. On the tested unit, a full device reboot restored raw
gesture events after the OEM input path stopped emitting them, but the new
cyclic mapping still requires one physical forward/backward test after import.
Do not treat scan codes 251 and 252 as universal directions.

Firefox's scroll response is a practical toolbar collapse, not a permanent
fullscreen or browser-policy guarantee. A slow page load or future Firefox
release can make the toolbar visible again.

## Files

- [`frame-mode-router.sh`](../examples/frame-mode-router/frame-mode-router.sh)
  contains the state machine and Android launch commands.
- [`frame-mode-router.example.conf`](../examples/frame-mode-router/frame-mode-router.example.conf)
  keeps deployment URLs outside the reusable script.
- [`keymapper-mode-router.example.json`](../examples/frame-mode-router/keymapper-mode-router.example.json)
  contains two disabled Key Mapper rules for review.

## Back up before changing Key Mapper

Export **all** existing Key Mapper rules and move the resulting ZIP off the
frame. Verify that the ZIP opens and contains `data.json`. Preserve any rule
that approves Android's USB-debugging dialog; that is a recovery path on this
no-touch device.

Use **Append** for the example import. `Replace` deletes the live rule list
before inserting the imported list and should be reserved for a deliberate,
verified full backup restore.

## Install the router

Prepare a private deployment copy of the example config on the computer:

```sh
cp examples/frame-mode-router/frame-mode-router.example.conf /tmp/frame-mode-router.conf
${EDITOR:-vi} /tmp/frame-mode-router.conf
```

Replace the three `example.invalid` URLs with the frame-accessible Home
Assistant routes. Do not add a password, access token, camera URL, or other
credential. The config is sourced as trusted shell input: write it yourself,
keep it private, and never install one received from an untrusted source. Then
install both files over an explicitly targeted USB ADB connection:

```sh
adb -s DEVICE_SERIAL push examples/frame-mode-router/frame-mode-router.sh \
  /data/local/tmp/frame-mode-router.sh
adb -s DEVICE_SERIAL push /tmp/frame-mode-router.conf \
  /data/local/tmp/frame-mode-router.conf
adb -s DEVICE_SERIAL shell chmod 700 /data/local/tmp/frame-mode-router.sh
adb -s DEVICE_SERIAL shell chmod 600 /data/local/tmp/frame-mode-router.conf
```

Exercise every destination directly before assigning hardware input:

```sh
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh show photos
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh show home
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh show cameras
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh show calendar
adb -s DEVICE_SERIAL shell sh /data/local/tmp/frame-mode-router.sh status
```

The default state and lock paths are under `/data/local/tmp`. A foreground
Fully activity always overrides stale state as `photos`; Firefox uses the last
saved Home Assistant mode. Missing or malformed state safely defaults to
`home`.

## Record and map the inputs

Keep USB attached and record the frame's actual events while performing one
gesture in each direction:

```sh
adb -s DEVICE_SERIAL shell getevent -lt
```

Import the example JSON with **Append**, inspect both disabled rules, and
retarget or swap their triggers to match the recorded device and direction.
The actions must remain:

```text
sh /data/local/tmp/frame-mode-router.sh next
sh /data/local/tmp/frame-mode-router.sh prev
```

Set each Key Mapper `Execute with ADB` timeout to at least 30,000 ms as a
precaution for slow camera-page loads on this hardware. Enable the rules only
after confirming that no existing global rule uses the same trigger.

The separate
[physical-key navigation profile](home-assistant-wall-panel.md#optional-physical-key-navigation)
can map Volume Down, Volume Up, and Star to `Tab`, `Shift+Tab`, and `Enter`
inside a view. Keep those browser-focus actions separate from mode routing.

## Reboot and Expert Mode

Key Mapper's accessibility setting and rule list persist, but Expert Mode's
ADB sysbridge did not auto-start after an ordinary reboot on the tested Android
10 firmware. With trusted USB ADB attached, open Key Mapper's Expert Mode page
and run the exact command it displays. For Key Mapper 4.3.1 FOSS it was:

```sh
adb -s DEVICE_SERIAL shell sh \
  /data/user_de/0/io.github.sds100.keymapper/start.sh
```

Verify that Key Mapper says `Running`, no rule says `Trigger device not
connected`, and the cloned gesture input exists before testing a swipe. This
manual bridge step is also why the router is not a reboot-proof substitute for
physical maintenance access.

Wireless ADB is privileged shell access and did not survive reboot on the
locked tested firmware. Do not depend on it for the Expert Mode restart, and do
not leave TCP ADB enabled on a general-purpose LAN.

## Operation and recovery

Rapid duplicate transitions are rejected by an atomic lock directory. If a
process is interrupted and leaves that exact lock behind, first confirm that no
router command is running, then remove only
`/data/local/tmp/frame-mode-router.lock`.

If the wrong direction opens:

1. Disable both router rules.
2. Confirm the raw scan code and input-device identity again.
3. Swap the two triggers; do not rewrite the mode order.
4. Re-enable one rule at a time and test the full loop.

For rollback, disable the two router mappings and restore the verified Key
Mapper export. The script and config can remain inert. Do not clear Fully,
Firefox, WebView, or Key Mapper app data as routine troubleshooting; those
actions remove useful sessions and recovery state.
