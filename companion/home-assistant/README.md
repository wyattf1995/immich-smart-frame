# Frame Companion Home Assistant package

This reusable package adds health sensors and authenticated frame controls.

Include `frame_server_health.yaml` as a second package for NAS audio freshness and
Kiosk, BirdNET, and audio-bridge restart counts. The NAS publisher described in
`../../birdnet/FRAME_HEALTH_PUBLISHER.md` supplies its source entity. The dashboard
marks these values unavailable after 180 seconds without a current report and
rejects future timestamps. Restart counts refer to the current container and can
reset when it is replaced. Render and recovery sensors use the existing private
companion snapshot; the last failure is historical, including recovered outages.
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

The package includes a disabled-by-default morning scene. Enable `input_boolean.frame_morning_scene_enabled`, choose a time with `input_datetime.frame_morning_scene_time`, then choose either `weather` or `weather_then_calendar`. The enable switch deliberately returns to off after a Home Assistant restart; the selected time and choice retain their saved values.

The scene lasts at most five minutes: Weather for five minutes, or Weather and Calendar for two and a half minutes each, before returning to Photos. Immediately before every view command it forces a companion refresh and requires a current server timestamp, current device presence, online status, active mode, and no pause, hold, or offline state. A refresh error or an old snapshot fails closed. If someone switches views, pauses, holds, or loses connectivity during the scene, it stops and does not force a return to Photos. It is intentionally not a wake-up, retry, or offline-recovery mechanism.
