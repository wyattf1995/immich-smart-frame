# Troubleshooting

## The image looks soft

Confirm the physical panel dimensions and the browser-reported client size.
On the tested frame, 1920x1080 physical pixels appeared as a 960x540 CSS
viewport at device-pixel ratio 2. The DPR patch should make Kiosk receive
`client_width=1920` and `client_height=1080`.

Keep both settings enabled:

```yaml
use_original_image: true
optimize_images: true
```

This fetches the best Immich source on the Docker host and resizes it to the
physical target. Do not use an Android display-density override as a quality
fix; it changes the whole UI and caused loading failures on the tested frame.

## Black screen or permanent loading indicator

Check the layers in order:

1. Open `http://DOCKER_HOST:3000/` from another LAN device.
2. Run `docker compose ps` and `docker compose logs --tail=100`.
3. Confirm the frame can reach the Docker host.
4. Force-stop and restart the browser without clearing its data.
5. Inspect Android logs for foreground-service suppression.

On MediaTek firmware, a DuraSpeed log containing
`bringUpServiceLocked, suppress to start service!` indicates the workaround in
[Device setup](device-setup.md) may be needed.

## Browser is extremely slow after Bluetooth pairing

Turn Bluetooth off and retest. A failed pairing on the verified frame caused a
continuous SystemUI bond-retry and wakelock storm. Use a USB-C OTG mouse for
recovery.

## Flower icon with a red slash

That is Immich Kiosk's offline/reconnect indicator. It can appear briefly while
the container restarts or the Docker host is busy building. If it persists,
check Kiosk logs and network reachability.

## A profile returns an error

- Confirm its name exists under `curation.profiles`.
- Confirm every tag value already exists in Immich.
- Confirm person and album IDs belong to the selected Immich user.
- Confirm date values use `last-X`, `today`, or
  `YYYY-MM-DD_to_YYYY-MM-DD`.
- Confirm at least one source has a positive weight.

The starter profile avoids tag, person, and album dependencies.

## Location is missing

Kiosk uses city/state from Immich EXIF metadata. GPS coordinates alone may
still need Immich reverse-geocoding before those labels exist. Photos with no
city/state intentionally render no empty location icon.

## Wireless ADB disappeared

That is expected after reboot on the locked stock firmware. Reconnect USB,
authorize if needed, and run `adb tcpip 5555` again. Wireless ADB is a setup
convenience, not a slideshow dependency.
