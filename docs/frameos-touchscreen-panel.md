# FrameOS touchscreen panel example

`examples/frameos/frameos-touchscreen-panel.html` is an opt-in iframe wrapper
for a Home Assistant dashboard mounted at `/frameos-touchscreen`. It deliberately
does not replace the existing `/wall-panel` deployment.

The companion YAML is a separate dashboard definition. It needs the live entity
registry before use: replace `camera.frameos_camera_1` through
`camera.frameos_camera_4` with the four chosen non-doorbell camera entities.
The camera chooser uses tiles only. Each hidden camera subview contains exactly
one `picture-entity` card with `camera_view: live`, so visiting a camera mounts
one live stream rather than four concurrent streams on the 1 GiB PF1007.

The thermostat uses `climate.entryway_entryway`. It has no automation or service
action in this example; any temperature or HVAC change is an explicit touchscreen
action from Home Assistant's standard thermostat card.

Use a same-origin authenticated Home Assistant route. The wrapper accepts only
same-origin iframe content (`frame-src 'self'`), makes no network requests, keeps
no credentials, and has a bounded loading observer. Verify actual camera playback,
thermostat permissions, authentication, and Android 6 WebView memory use on the
QA frame before deploying to bedroom frames.
