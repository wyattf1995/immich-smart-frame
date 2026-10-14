# Android app setup and builds

The two FrameOS apps have distinct application IDs; the offline Touch Probe
has a third, diagnostic-only ID. Choose the app and hardware target
explicitly; the latest source is not necessarily a released or device-tested
APK. Read the [compatibility table](compatibility.md) first.

| App | Gradle target | Minimum / packaged native ABI | Configuration and identity |
| --- | --- | --- | --- |
| FrameOS modern | `:app`, default `frameosTarget=modern` | API29 / ARM64 | `com.wyattfleming.frameos` |
| FrameOS legacy | `:app`, `frameosTarget=legacyArmv7` | API23 / ARMv7 | Same package as modern; alternatives, not parallel installs |
| FrameOS Touch | `:touchframe` | API23 / ARMv7 Gecko through `:frame-shared` | `com.wyattfleming.touchframe`; independent data and login |
| Touch Probe | Separate `touchprobe` Gradle root | API23 / no native libraries | Offline diagnostic; [own instructions](../frameos/touchprobe/README.md) |

Minimum API is an installation floor, not a promise that all hardware, WebViews
or current Android permission behavior have been accepted. Touch does not become
an ARM64 app by passing `frameosTarget=modern`; that property selects only `:app`.

## Shared prerequisites

1. Establish model-specific app access using [hardware recovery](hardware-recovery.md).
2. Confirm the device's actual API level, supported ABIs and display dimensions.
3. Set up the [browser photo service](../README.md#quick-start) and verify it from
   another LAN client. Home Assistant, Art and Birds are optional services with
   their own configuration.
4. Keep credentials, signing material and device configuration outside Git. Use
   the documented provisioning path for the chosen app; do not copy encrypted
   app data or Keystore material between packages.
5. Keep a working launcher and a way back to Android Settings while evaluating
   a new app. Building does not install, select HOME, root or flash a device.

## FrameOS existing app

Use [FrameOS mode router](frame-mode-router.md) for Lenovo navigation and setup,
or [existing touchscreen panel](frameos-touchscreen-panel.md) for its touch
adaptation. [Home Assistant wall panel](home-assistant-wall-panel.md) covers
the shared dashboard wrapper; [Art mode](frameos-art-mode.md) covers the
separate artwork feed. These guides describe `com.wyattfleming.frameos`.

Build in an admitted Android environment with the checksum-pinned Gradle wrapper,
Java 17 or a compatible JDK, and the SDK required by the checked-in build files.
The source currently compiles against API37. From `frameos/`, choose one command:

```sh
# Modern / ARM64 (the default).
./gradlew --no-daemon --max-workers=2 -PframeosTarget=modern \
  :app:testDebugUnitTest :app:lintRelease :app:assembleRelease

# Legacy / ARMv7 (explicit opt-in; separate clean output workspace).
./gradlew --no-daemon --max-workers=2 -PframeosTarget=legacyArmv7 \
  :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Both target selections write `app/build/outputs/apk/release/app-release-unsigned.apk`
and `app/build/outputs/mapping/release/mapping.txt`. Build them in separate clean
workspaces, retain each separately, and label artifacts with target, source
revision and hash. Never identify modern versus legacy from the filename alone.
Keep the documented rollback/version pins until their reason is disproved.

These commands compile, lint and run JVM tests. They do not replace the applicable
source-only regression proofs, signing verification or device release gates.
The reviewed legacy build harness is
[`scripts/frameos-legacy-android-nas.sh`](../scripts/frameos-legacy-android-nas.sh).
It requires its admitted Linux Android runtime and is not a universal host setup
script. [Initial preparation](touchscreen-preparation.md) documents a historical
modern/probe build, not acceptance of today's source.

## FrameOS Touch

Read the [Touch architecture, provisioning and acceptance guide](frameos-touch-app.md).
The new shell uses native Home and its own navigation. It is a development
candidate; existing FrameOS device results do not certify it.

From `frameos/` in an admitted Android build environment:

```sh
./gradlew --no-daemon --max-workers=2 \
  :frame-shared:testDebugUnitTest :touchframe:testDebugUnitTest \
  :touchframe:lintRelease :touchframe:assembleRelease
```

Outputs are `touchframe/build/outputs/apk/release/touchframe-release-unsigned.apk`
and `touchframe/build/outputs/mapping/release/mapping.txt`. The reviewed Linux
runner [`scripts/touchframe-android-nas.sh`](../scripts/touchframe-android-nas.sh)
also executes source-only negative/restored-green proofs and retains source,
SDK and JUnit identities. It builds but does not sign or deploy.

Touch has independent OAuth and companion provisioning. The source declares an
ordinary user-selectable Android HOME entry point: explicit HOME starts Photos,
while ordinary app launch starts native Home. This does not establish launcher or
boot acceptance; keep the working app and vendor launcher available, and do not
enable old and new companion pollers with one shared device identity. Camera source
includes an explicit one-camera live view with authenticated snapshot fallback.
Complete the guide's physical touch, offline, Settings-return, live-camera, and
boot-recovery gates before replacement.

## Internal sharing and maintenance

`:frame-shared` is an internal bridge that selects reviewed source, tests and
assets from `:app`. It is not a separately released SDK or a copy of the Lenovo
activity. Changes to selected files need validation in both consumers. Preserve
this explicit boundary while its API settles; an app rename or source move is a
separate refactor, not a documentation prerequisite.

Heavy builds use the maintainer's admitted workers and resource limits. There
are no GitHub Actions or automatic releases. See
[component release steps](releasing.md) for exact evidence and distribution rules.
