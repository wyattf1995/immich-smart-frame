# Frame TV

A small, open-source Android TV home screen for older televisions. Streaming apps
are the primary experience; household dashboards open only when selected.

Built for Android 9 and newer, including 32-bit Android installations. It uses
native Android Views and the installed system WebView, with no bundled browser,
analytics, ads, background service, accessibility interception or wake lock.
The repository's AGPL-3.0 license applies.

## First version

- Launch installed TV apps from a five-column favorites grid. Plex and common
  streaming services start as favorites when installed; saved favorites are
  preserved. This does not install apps or create streaming subscriptions.
- A still gradient landscape follows the FrameOS weather palette. Native paths
  and translucent cards add atmosphere without a background animation loop.
- Hold Select on an app to add or remove it from Favorites.
- Open HDMI inputs exposed by the TV's input service.
- See all six home cards at once: Photos, Home, Weather, Cameras, Calendar and
  Birds use existing dashboard
  addresses. Every feature is optional; streaming works before setup.
- Reach the TV's settings and original home screen from the System row.
- Select Refresh apps after installing or removing another application.

The launcher creates no WebView. Opening a configured dashboard creates one;
leaving that activity destroys it immediately. Browser cookies and local storage
remain in this app's private WebView profile so a dashboard can remember sign-in.
Dashboards fill the screen. A Menu key can reveal temporary Back/Reload controls;
Back dismisses that menu, then returns to Frame TV. Loading/error controls overlay
the page and never reduce the page's viewport. Remote Menu availability varies by
TV; Back and reopening a card remain the basic navigation and recovery path.
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

Birds opens the existing read-only Birds Around Us frame viewer. Use its viewer
address, not the BirdNET administration interface. The viewer and its detector
health/identification labels remain authoritative; detections are model
candidates unless independently confirmed. The app needs no BirdNET credential.

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
and a source-only negative check of partial-row remote navigation. It restores
exact source bytes, reruns the tests, compiles an **unsigned** debug APK,
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

### Sony XBR-65A8F limitation verified on Android 9

The system launcher has HOME intent priority 2. Android accepts and saves the
Frame TV preference, but the resolver and physical Home key still choose Sony's
launcher. Always verify the actual Home key; a `Success` command response is
insufficient. The tested preference was restored to the stock component.

Android 9 chooses a higher-priority activity before consulting the saved
preference and caps normal apps' positive filter priorities to zero. See
[the Android 9 resolver source](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r30/services/core/java/com/android/server/pm/PackageManagerService.java).
This prototype leaves the stock launcher enabled, so launch Frame TV as a normal
app on this model. Disabling the stock launcher would remove the current Sony
Home escape and needs a separately designed and verified recovery path.

### Forest home and native forecast (0.3.0 draft)

The home screen uses one still forest-floor photograph, decoded once at bounded
resolution (1672 × 941, RGB565). A mild vertical shade keeps text readable without
an animated background or blur pipeline. Original artwork and the built-in image
generation prompt are in `artwork/`; the packaged JPEG is in `drawable-nodpi/`.
The compact forecast sits above a scenic gap; home dashboards and the five-column
streaming favorites occupy the lower shelf. Inputs and system actions follow;
All apps is the final section. Physical Sony Home behavior remains as documented
above. Combined build/device validation is pending.

The native widget uses Open-Meteo over HTTPS: current conditions, next six hours
and five days in Fahrenheit and the forecast location’s timezone. Select the
widget to search and choose a city/ZIP. Configuration and a small cache live in
private preferences; no location or credentials are bundled with the APK. Cache
reuse is bounded to six hours, with a 30-minute refresh only while the launcher
is resumed. Source-scoping and midnight rollover have source-negative JVM proofs.
The existing Home Assistant dashboard login is independent of this native feed.
Official provider documentation: https://open-meteo.com/en/docs and
https://open-meteo.com/en/docs/geocoding-api.
