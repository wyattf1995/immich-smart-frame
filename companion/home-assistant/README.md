# Frame Companion Home Assistant package

This reusable package adds health sensors and authenticated frame controls.
Provision it separately for each Home Assistant installation.

Add `homeassistant: { packages: !include_dir_named packages }` to `configuration.yaml`, place `frame_companion.yaml` in `packages/`, and add these secret values to `secrets.yaml`:

```yaml
frame_companion_state_url: "https://frame.example/api/state"
frame_companion_command_url: "https://frame.example/api/command"
frame_companion_event_url: "https://frame.example/api/event"
frame_companion_authorization: "Bearer replace-with-private-token"
```

Run `ha core check` before restarting Home Assistant. The event scripts are manual-only and send one of `calendar` or `reviewed_bird` with a 120-second server expiry. They do not create notifications or automatic bird triggers.

Import `frame_companion_dashboard.yaml` as a YAML dashboard, or copy its cards into an existing dashboard. It contains no credentials.

## Optional morning scene

The package includes a disabled-by-default morning scene. Enable `input_boolean.frame_morning_scene_enabled`, choose a time with `input_datetime.frame_morning_scene_time`, then choose either `weather` or `weather_then_calendar`.

The scene lasts at most five minutes: Weather for five minutes, or Weather and Calendar for two and a half minutes each, before returning to Photos. It starts only when the companion reports the frame online, in active Photos, and not using offline photos. A manual pause or active photo hold reports `photosPaused`, so the scene skips it. Before each later transition it checks that the frame is still in the view started by the scene; if someone switches views, it stops and does not force a return to Photos. It is intentionally not a wake-up, retry, or offline-recovery mechanism.
