# FrameOS Touch changes

Applies only to `com.wyattfleming.touchframe` (`:touchframe`). This package has
its own release history and Android version fields. Existing FrameOS release
or device results do not apply automatically.

## Unreleased — initial development candidate

- Calendar starts in Week with dated event columns and locally paged day details.
  Agenda and Month remain available; Home Assistant supplies calendar events.

- Separate touchscreen shell with native Home, direct mode navigation and
  an explicit route back from Android Settings.
- Shared authentication, weather, photo and companion components through the
  internal `:frame-shared` module.
- Fullscreen Photos/Art, bounded offline storage, calendar, camera snapshots
  and optional Birds source paths.

Earlier candidates have been built and QA-installed, with scoped device
checks recorded in the Touch guide. Full acceptance of the current combined
source remains separate. No public Touch release is established by this entry. See the
[Touch guide](../../docs/frameos-touch-app.md) for current gates and
[release policy](../../docs/releasing.md) for artifact and evidence requirements.
