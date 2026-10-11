# Touchscreen preparation

These are development artifacts for physical-device evaluation. Building an APK
is not proof of installation, rooting, firmware compatibility, or reliable touch
handling on an untested frame.

## Two separate artifacts

- **FrameOS** keeps its Android 10/API29 minimum and ARM64 Gecko renderer. It adds
  mode navigation from deliberate inward swipes starting within 24dp of a left
  or right edge and traveling at least 96dp. Leftward from the right edge selects
  the next mode; rightward from the left edge selects the previous mode. Normal
  web taps, center swipes, vertical scrolling, and multi-touch remain with the
  content. Existing key and Lenovo OEM controls retain their behavior.
- **FrameOS Touch Probe** is a separate, offline Android 6/API23 app with no
  native libraries, network permissions, or launcher replacement. It reports the
  actual API/ABI, display, memory and touch capabilities and offers native touch
  and bundled WebView checks. See [probe instructions](../frameos/touchprobe/README.md).

The [matching PF1007L firmware report](https://github.com/wesbos/photo-frame-dashboard/blob/main/docs/allwinner-a33-pf1007l.md)
uses Android 6/ARMv7. Those properties still need to be queried on the actual
frame. The current FrameOS APK cannot run on that reported configuration; the
probe helps determine the backport or replacement-OS work required. The larger
frame's exact OS and ABI also remain unverified.

## Build and retained evidence

Run `bash scripts/touchscreen-prep-android-nas.sh` inside the admitted Android NAS
runtime. It reuses the reviewed, checksum-pinned SDK bootstrap and existing
FrameOS/TV regression harness, then runs touch JVM guards with a source-only
negative control and restored green run. The standalone probe uses the image's
API36 SDK. Local heavy builds and emulators are unnecessary.

The outputs are unsigned; signing is a separate local step. Export the two APKs,
FrameOS mapping, `reports/frame-sync-android-validation.zip`, and
`reports/touchscreen-prep-validation.zip` with source identity and NAS receipts.
No household configuration, credentials, signing keys, or personal media belong
in the source upload. Do not deploy these artifacts automatically.

## Device acceptance once access is available

1. Record the physical model, API, ABI, memory, screen size/density and current
   launcher. First confirm ordinary app installation without replacing the
   launcher or modifying firmware. Preserve the stock device state.
2. Run the probe. Check single and multiple fingers, vertical movement inside the
   visualizer, edge reach, orientation, offline WebView rendering and actual tap
   reporting. Repeatedly open/close the WebView screen; it must release its view.
3. Only install full FrameOS if its API and ABI requirements are met. Check both
   edge directions, a second swipe after release/cancel, and leaving/returning to
   the app mid-gesture. Each qualifying swipe must navigate exactly once.
4. On a scrollable Home Assistant/Gecko page, verify ordinary taps, vertical and
   center horizontal scrolling, and multi-touch remain usable. Verify an edge
   swipe still navigates even after the web child requests disallowed parent
   interception, and that cancellation does not leave the web page dragging or
   unresponsive. This Android dispatch behavior is not established by JVM tests.
5. Recheck key/OEM mode switching and volume controls on supported existing
   hardware before any rollout. Check idle return, network reconnect, renderer
   memory and long-running behavior separately from compilation.

Firmware access and SD-card preparation are separate from this app work. A FEL
card alone does not root the device or provide a verified firmware backup.
