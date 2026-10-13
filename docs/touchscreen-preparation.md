# Historical touchscreen preparation

> **Historical evidence, not a current deployment guide.** For the current public
> model matrix and recovery/access boundary, see [display compatibility](compatibility.md)
> and [hardware recovery](hardware-recovery.md). FrameOS Touch is a separate source
> candidate documented in [FrameOS Touch](frameos-touch-app.md).

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

## Historical build and retained evidence

The historical build used a checksum-pinned Android build environment and
existing FrameOS/TV regression harness, including touch JVM guards with a
source-only negative control and restored-green run. Its standalone probe was a
separate offline API23 artifact.

Those outputs were unsigned; signing and deployment were intentionally separate.
Do not treat this historical record as a command to rebuild, export, or deploy an
artifact. Any future build must use its own reviewed release plan and preserve
household configuration, credentials, signing keys, and personal media outside
the source upload.

### Verified overnight build — 2026-10-11 UTC

A retained historical preparation build compiled and linted cleanly. It recorded
267 existing FrameOS JVM tests, 24 TV JVM tests, 30 JavaScript tests, and nine
touch guards. The guards passed, failed under a deliberate source-only
input-gate reversal, and passed again after exact source restoration with
unchanged test hashes.

The outputs were unsigned development artifacts. They were independently
verified and archived before their build workspace was retired. These facts
preserve the original preparation evidence; they do not establish installation,
firmware compatibility, launcher replacement, root access, or physical touch
acceptance on any frame.

No frame installation, firmware change, launcher replacement, or production
signing occurred as part of that historical preparation run.

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
