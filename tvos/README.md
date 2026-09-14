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

These views must already exist. Calendar opens its configured calendar route directly in the existing signed-in
profile. Weather opens a native full-screen forecast; its legacy web URL is kept
for configuration compatibility but is not used by the Weather tile.
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

First install and test as an ordinary TV app. Record the existing HOME component
and verify the actual Home key after changing its preference. Preserve Cast,
Sony services, launcher data, and a tested route back to the original homepage.
Disabling a stock launcher package is a separate, device-specific change that
requires the owner's explicit choice; do not apply it automatically to other TVs.
An LG webOS television cannot install this Android APK directly.

### Sony XBR-65A8F on Android 9

The stock component is `com.google.android.tvlauncher/.MainActivity`, with HOME
intent priority 2. Android accepts Frame TV's preference but still resolves to
that higher-priority stock activity. See
[the Android 9 resolver source](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r30/services/core/java/com/android/server/pm/PackageManagerService.java).
Activity-only disable and re-enable commands were rejected with `Shell cannot
change component state`; they are not a usable recovery procedure on this TV.

On September 13, 2026 Wyatt explicitly authorized the broader package switch.
Disabling only `com.google.android.tvlauncher` with `pm disable-user --user 0`
succeeded, and the HOME resolver selected Frame TV. The original application and
its data are retained. The host-side `Restore Sony Home.command` was then tested
against that actually disabled package: it enabled the package, set the stock
HOME preference, verified the resolver and sent Home successfully. Screenshots
show the system app-information page also offers Enable; the full remote recovery
flow and boot persistence require their own device checks.

The installed version remains 0.4.0. Draft source for 0.4.1 adds a Sony Home
helper that detects package-level disabling and
opens the original launcher's app settings with instructions to choose Enable
and press Home. The Mac restore script remains the alternate recovery path and
requires the authorized network ADB connection. No APK changes package states
itself, and no Cast receiver/service package is disabled. The 0.4.1 NAS build
was deferred because another operation held the shared runner; it has not been
built or installed. After testing the Mac rollback, the Frame TV switch was
reapplied and its resolver/resumed activity verified. The TV was left in standby.

Optional shared photo playback is implemented and has passed server/browser
validation; see [shared playback](PHOTO-SYNC.md) for configuration and rollout gates.
Each screen keeps its own `frame_id` and joins the same `sync_group`.

### Forest home and native forecast (0.3.3)

The home screen uses one still forest-floor photograph, decoded once at bounded
resolution (1672 × 941, RGB565). A mild vertical shade keeps text readable without
an animated background or blur pipeline. Original artwork and the built-in image
generation prompt are in `artwork/`; the packaged JPEG is in `drawable-nodpi/`.
The forecast follows the frame's translucent-card styling, with a large current
temperature and separate hourly/daily columns, condition pictograms, blue rain
percentages and labeled daily highs/lows. It uses static native views. The home
order is weather, five-column streaming favorites, Home dashboards, Inputs,
System, then All apps. There is no scenic-height spacer. Physical Sony Home
behavior remains as documented above. Version 0.3.3 was installed on the Sony
XBR-65A8F on 2026-09-13. Full-HD screenshots verified all ten favorites and Home
controls visible beneath the forecast; D-pad Down moved from Plex to Netflix,
Photos, then HDMI 4, scrolling as needed. Back restored weather focus at the top.
The NAS build passed 24 JVM tests, all source-negative guard checks and lint
(zero errors, 14 warnings). Signing and installed source identity were verified.

The native widget uses Open-Meteo over HTTPS: current conditions, next six hours
and five days in Fahrenheit and the forecast location’s timezone. Select the
widget to search and choose a city/ZIP. Configuration and a small cache live in
private preferences; no location or credentials are bundled with the APK. Cache
reuse is bounded to six hours, with a 30-minute refresh only while the launcher
is resumed. Source-scoping and midnight rollover have source-negative JVM proofs.
Home Assistant is preferred after its Home dashboard supplies a forecast-only
snapshot. While that dashboard is open, the bundled reader requests only the
current weather entity and its hourly/daily forecasts through HA's existing
connection; it does not read/copy authentication, create a JavaScript native
interface, or deploy anything to HA. The native widget reuses the snapshot for
at most six hours, scoped to the exact configured Home URL. It refreshes when
Home Assistant is opened, not through a background browser. If no usable HA
snapshot exists, the labeled Open-Meteo city forecast is the fallback.
Select the widget → Use Home Assistant forecast to refresh; choosing a city
explicitly selects Open-Meteo. Provider and cached age are visible on home.
The HA normalizer's local Node tests include a real source-negative Celsius
conversion check; compilation and JVM guard tests run on the NAS.
The existing signed-in Home dashboard supplied 48 hourly and six daily forecast
records on the test TV, and native home displayed Home Assistant attribution.
This verifies the forecast-only path; it does not imply background HA refresh.
Official provider documentation: https://open-meteo.com/en/docs and
https://open-meteo.com/en/docs/geocoding-api.


### Direct Weather and Calendar (0.4.0)

The Weather tile opens an API-28 native full-screen activity using the same bounded
forecast source as the home widget. Its still sky/terrain scene follows conditions;
current conditions, five daily forecasts and eight hourly entries fit the screen.
Left/Right page through the hours, Menu opens weather-source setup, and Back exits.
It creates no WebView, background service or wake lock. The HA snapshot/fallback
and refresh limits above still apply; opening Weather does not start HA.

Calendar directly loads the configured `/wall-panel/calendar?kiosk` route using
the existing sign-in profile. The month view was verified on the Sony without
first visiting the Home dashboard. HA's frontend still loads to display Calendar.
No Home Assistant server/dashboard configuration was changed.
