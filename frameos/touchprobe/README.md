# FrameOS Touch Probe

This is a separate Android diagnostic APK for older touchscreen frames. It is not
FrameOS and does not try to replace its Photos, Home Assistant, weather, or
companion features.

The app uses only Android platform APIs, has `minSdk 23`, and contains no native
libraries, network permission, credentials, persistent logs, device identifiers,
or `HOME` launcher intent. It reports OS/API level, supported ABIs, available
memory, display dimensions, and touch features. Its visualizer checks direct
multi-touch input. A second screen loads a bundled offline WebView page, so the
browser engine and its touch path can be checked without a LAN endpoint or a
network request.

It is a useful installation and hardware-capability probe for PF1007/PF1007L
units documented as Android 6/API 23/ARMv7. It may also run on another Android
frame, but a successful launch does **not** verify rooting, USB/ADB access,
bootloader unlockability, installer policy, display reliability, or compatibility
with FrameOS. Those require access to the physical frame and later device tests.

## Build

The probe is an independent Gradle root so its `compileSdk 36` build does not
configure the modern FrameOS app, which currently needs API 37. From `frameos/`:

```sh
./gradlew -p touchprobe :app:assembleRelease --no-daemon --max-workers=2
```

This produces an intentionally unsigned release APK at:

```text
touchprobe/app/build/outputs/apk/release/app-release-unsigned.apk
```

Use the NAS Android runtime for builds after committing the source and declaring
that APK as the sole exported artifact. Do not add signing material to this
project or NAS job.
