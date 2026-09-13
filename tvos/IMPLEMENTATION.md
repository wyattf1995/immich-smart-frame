# Frame TV first build

Target: Sony XBR-65A8F, Android 9/API 28, 32-bit Android, 2 GB RAM.
This independently built Android application lives beside FrameOS and inherits
the repository's AGPL-3.0 license. Existing frame app, services and devices stay
outside this implementation's write/test scope.

## Scope

- Native remote-controlled home: installed TV apps, Plex first, HDMI inputs,
  clock, Photos/Home/Weather/Cameras/Calendar cards and settings.
- Streaming works without household configuration or network dashboard access.
- Optional URLs configured locally. One WebView exists only while viewing an
  explicitly opened dashboard. Destroy it when the activity stops; pause it
  immediately on pause. No background services, wake locks, alarms, polling,
  boot receiver, accessibility takeover, video wallpaper or bundled browser.
- Preserve Cast receiver and Sony audio/input/picture settings. Leave stock
  launcher installed and accessible. Validate as an ordinary app before setting
  it as HOME. No package disabling/debloat or idle protection changes.
- No tokens, household endpoints or datasets in committed source/NAS uploads.
  Dashboard auth occurs in the renderer. No JS-native bridge, ignored TLS
  errors, file/content access, automatic external intents or cross-origin
  credential injection. Local HTTP dashboard endpoints are permitted explicitly
  because existing household services use LAN HTTP.

## Java interfaces between implementation lanes

Package `com.wyattfleming.frametv`.

Parent owns `Feature.java`, `TvPreferences.java`, `UrlPolicy.java`,
`DashboardActivity.java`, `SetupActivity.java`, manifest/build files and docs.

Launcher lane owns `MainActivity.java`, `AppCatalog.java`, `TvUi.java` and
`LauncherOrder.java` only. Native framework Views, no added dependencies.

`Feature` enum: PHOTOS, HOME, WEATHER, CAMERAS, CALENDAR, each with public
`String title` and `String description`.
`TvPreferences(Context)` exposes `String url(Feature)` and
`boolean configured(Feature)`, plus `Set<String> favorites()` and
`void toggleFavorite(String packageName)`; Plex is initially favorite.
Launch dashboard with explicit Intent to `DashboardActivity`, string extra
`feature` = enum.name(). Both Dashboard/Setup activities are non-exported.
Launch setup with explicit Intent to `SetupActivity`.

`TvUi`: static `int dp(Context,int)`, `TextView text(Context,String,int,int)`,
`Button button(Context,String,Runnable)`, `void window(Activity)`; colors
`BG`, `PANEL`, `TEXT`, `MUTED`, `ACCENT` as public static final ints.
Parent uses only these shared UI APIs.

`LauncherOrder` pure Java helper: `List<String> order(List<String> packages,
Set<String> favorites)`; favorites first, Plex first within favorites; remaining
packages stable in input order, duplicates removed. No absent package added.

## Verification

Compile, JVM unit tests and lint in admitted NAS Android runtime from clean
draft commits, with two Gradle workers. Export unsigned APK, test XML/lint report,
sanitized build log and source identity. Sign locally, inspect package/SDK/native
libraries, then device-test only the authorized Sony TV. Capture screenshots,
remote focus/app/HDMI transitions and foreground/background memory. Distinguish
observed launcher improvements from unmeasured third-party app performance.
