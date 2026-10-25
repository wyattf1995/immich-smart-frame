# Touch live-camera recovery and acceptance

The camera recovery candidate at `7931d7f28d3b6a1b8bc6c1d404c1596fc8f3a507`
preserves the native Touch design and adds explicit connection and failure
states. On October 14, 2026, the ARMv7/API23 candidate passed release compilation,
lint, and 316 JVM tests across 83 report files, with no failures, errors or skips.
The retained source-only negative/restored-green checks cover 53 base cases,
14 design mutations, one weather-header guard, one startup guard, three climate
guards, and three Node plus three JVM camera mutations. Transport and restored
source hashes match all 392 source files; APK, mapping and reports were verified
and archived before the original build workspace was retired.

The preceding run stopped because its proof harness expected only one failing
mode test from a mutation that affects two existing cases. The corrected harness
requires both exact failures and every other selected case to pass; no product
test was weakened or removed. Failed evidence was retained.

The candidate is QA-signed and prepared, not installed. This validation does not
establish working video or connection reliability on a physical frame.

## Expected behavior

1. Selecting a camera offers **Watch live** without requesting a snapshot.
2. Watch live shows native connecting controls above the Gecko surface until
   the video element reports actual playback.
3. Native startup has a 12-second deadline, including extension attachment and
   token handoff. Failure returns a retry control rather than retaining a black
   live surface.
4. Leaving the camera view revokes its capability and closes the owned session
   while the view is attached. Callbacks from a released generation cannot
   change the newly selected camera or screen.
5. The built-in extension version is `1.0.1`, so an existing installation can
   replace the packaged assets. Tokens remain in memory and never enter a URL,
   diagnostic message, or persistent page state.

The failure UI is a mitigation for an observed failed connection followed by
black content. Session teardown ordering is not a proven explanation for that
device's rendering behavior. A retry control does not establish stream support.

## Bounded diagnostics

The `TouchLiveCamera` log tag emits a fixed enum only. Milestones include native
grant, socket open, authentication, client configuration, offer, answer, ICE,
and playback. Failure categories identify the stage, not necessarily the root
cause. The native deadline emits `FAILED_TIMEOUT` before teardown. URLs,
camera entity IDs, credentials, SDP, and remote exception payloads are excluded.

For example, `NATIVE_GRANT` followed by `FAILED_SOCKET` before `SOCKET` means
the WebSocket did not open. That warrants transport diagnosis before changing
camera negotiation or permissions. Native Home requests succeeding does not
prove Gecko's WebSocket resolver, TLS, and route are identical.

## Physical-device acceptance still required

- Verify the installed APK and intended device identity, then preserve the
  existing configuration and authenticated session during an update.
- Confirm connecting, actual video playback, and usable retry controls after a
  failed connection. Test switching cameras and leaving during startup.
- Verify Home and Photos still respond after failure; inspect repeated exits
  for retained sessions and resource growth.
- Confirm normal power-cycle startup, network reconnection, and retained sign-in
  separately. An app-level camera test cannot establish ADB service reliability.

Some vendor Android init configurations stop ADB when USB configuration changes.
Static boot-image inspection only establishes that such a path exists. Capture
runtime service/USB state before choosing a recovery rule. A host cannot start a
stopped ADB service through the connection it has just lost. Any detached trace
must be launched while access is healthy and have a fixed duration and private
output. Device-specific observations and recovery helpers stay outside this
public repository; this candidate does not add a firmware patch or watchdog.
