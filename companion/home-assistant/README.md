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
