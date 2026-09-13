# Frame TV

A small, open-source Android TV home screen for older televisions. Streaming apps
are the primary experience; household dashboards open only when selected.

Built for Android 9 and newer, including 32-bit Android installations. It uses
native Android Views and the installed system WebView, with no bundled browser,
analytics, ads, background service, accessibility interception or wake lock.
The repository's AGPL-3.0 license applies.

## First version

- Launch installed TV apps. Plex starts as a favorite when installed.
- Hold Select on an app to add or remove it from Favorites.
- Open HDMI inputs exposed by the TV's input service.
- Open Photos, Home, Weather, Cameras and Calendar using your existing dashboard
  addresses. Every feature is optional; streaming works before setup.
- Reach the TV's settings and original home screen from the System row.
- Select Refresh apps after installing or removing another application.

The launcher creates no WebView. Opening a configured dashboard creates one;
leaving that activity destroys it immediately. Browser cookies and local storage
remain in this app's private WebView profile so a dashboard can remember sign-in.
No setting keeps the display awake, so Android/Sony screensaver and panel
protection can operate normally.

The new home screen can reduce launcher overhead; it cannot upgrade the TV's CPU,
make every streaming app faster, or establish a fix for random shutdowns.

## Dashboard setup

Choose Configure or an unconfigured feature card. Enter the address of each
existing dashboard and save. HTTPS is supported; HTTP is limited to private IPv4,
loopback and `.local` addresses. Passwords, user information and common secret
URL parameters are rejected. Do not enter secret-bearing links under other names.

For an existing Home Assistant installation, typical routes are:

```text
https://your-home-assistant.example/wall-panel/home?kiosk
https://your-home-assistant.example/wall-panel/cameras?kiosk
https://your-home-assistant.example/wall-panel/calendar?kiosk
```

These views must already exist. Weather currently opens a configured web page;
the frame's native cached weather integration is not part of this first build.
Photos opens an existing Immich Kiosk page. It does not copy the frame's offline
photo reserve, extensions, gesture controls, or native overlays.

Use the dashboard's own sign-in page. Cross-origin navigation, popups, external
provider sign-in, JS-native bridges, file/content access and ignored TLS errors
are not supported. Home Assistant's local account sign-in can work on its own
origin; an external OAuth provider needs a future dedicated integration. Camera
codec support and dashboard keyboard accessibility depend on the TV's WebView
and the web page. Back always returns to Frame TV; Reload recovers a failed page.

## Build and verification

Pinned toolchain: JDK 17, Gradle 8.11.1, AGP 8.10.1, SDK/build tools 36. It runs
in the admitted NAS Android runtime with two Gradle workers:

```sh
bash tvos/tools/nas-build.sh
```

The script runs JVM tests, two source-only negative checks of URL security,
restores exact source bytes, reruns the tests, compiles an **unsigned** debug APK,
and runs Android lint. It emits source hashes, APK identity and test/lint logs.
No signing key or household configuration belongs in the NAS snapshot. Signing
is a separate local step.

For NAS transport, retain an exact committed `tvos` subtree plus the repository
LICENSE in a dedicated build-only Git commit, recording the original integration
commit and verifying the subtree object ID matches. This avoids transferring the
unrelated frame/server deployment tree. Never merge that reduced build snapshot
back into the full repository. Land implementation branches using Git merge.

Device acceptance must separately check D-pad focus, app/HDMI launches, Back/Home,
return from playback, photo rendering, dashboard authentication and renderer
teardown. Compilation and memory snapshots do not prove Chromecast playback or
long-term responsiveness. A physical Lenovo frame is not a TV QA device.

## Reversible home-screen selection

First install and test as an ordinary TV app. Keep the stock launcher and all
Cast/Sony system packages enabled. Record the existing HOME component before
changing the default through the supported Android package command or UI. Restore
that exact component to roll back. Never disable vendor packages as a shortcut.

On the initial Sony test unit the observed previous component was
`com.google.android.tvlauncher/.MainActivity`; confirm it again on other TVs.
An LG webOS television cannot install this Android APK directly.
