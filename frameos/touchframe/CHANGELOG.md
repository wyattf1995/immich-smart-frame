# FrameOS Touch changes

Applies only to `com.wyattfleming.touchframe` (`:touchframe`). This package has
its own release history and Android version fields. Existing FrameOS release
or device results do not apply automatically.

## Unreleased — initial development candidate

- Calendar starts in Week with dated event columns and locally paged day details.
  Agenda and Month remain available; Home Assistant supplies calendar events.
- Native Home uses the gallery-wall day/night theme and a persistent, horizontally
  scrollable top navigation bar. Full-screen Photos and Art reveal it by a left-edge
  gesture; Android HOME starts Photos while ordinary app launch starts Home.
- Climate parsing supports older Home Assistant entities that provide the temperature
  unit only through `/api/config`. Target changes remain disabled for absent targets,
  valid ranges, and `heat_cool` states.
- Camera source includes an explicit single-camera live surface with a packaged local
  page and one-shot capability, while retaining authenticated snapshot fallback.

- Separate touchscreen shell with native Home, direct mode navigation and
  an explicit route back from Android Settings.
- Shared authentication, weather, photo and companion components through the
  internal `:frame-shared` module.
- Fullscreen Photos/Art, bounded offline storage, calendar, camera support,
  and optional Birds source paths.

Earlier candidates have been built and QA-installed, with scoped device
checks recorded in the Touch guide. Full acceptance of the current combined
source remains separate. No public Touch release is established by this entry. See the
[Touch guide](../../docs/frameos-touch-app.md) for current gates and
[release policy](../../docs/releasing.md) for artifact and evidence requirements.
