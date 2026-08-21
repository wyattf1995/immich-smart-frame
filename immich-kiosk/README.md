# Lenovo Smart Frame + Immich Kiosk

This project turns a discontinued Lenovo Smart Frame into a native-resolution,
fullscreen Immich display with a selection algorithm that is actually under the
owner's control.

The frame remains a deliberately thin client: Fully Kiosk Browser renders a web
page, while an Unraid NAS runs a pinned custom build of Immich Kiosk. No photo
files, tags, or sidecars are modified by this project.

## What is custom

The image pins upstream Immich Kiosk `v0.42.0` and applies two reviewable patches:

1. `fully-kiosk-dpr.patch` multiplies the CSS viewport by
   `window.devicePixelRatio`. The Lenovo panel is physically 1920x1080, but its
   Android WebView reports a 960x540 CSS viewport at DPR 2. Without this patch,
   Kiosk generated a 960x540 image that Android stretched 2x.
2. `weighted-curation.patch` adds named curation profiles whose source weights
   are direct relative probabilities. Sources may be an Immich tag, person,
   album, date range, memories, or the global random pool. A URL can select a
   different profile for each frame.

`weighted-curation-tests.patch` is kept separately from the implementation and
is run during every image build.

## Selection model

The active `balanced` profile uses tags produced by a local Qwen VLM pass,
Immich's recognized-face data, and an explicit recency ladder. Its weights
total 100:

- 25% photos captured in the last 30 days
- 15% photos captured in the last 180 days
- 10% photos captured in the last 730 days
- 22% people/faces
- 6% dogs
- 9% travel and family events
- 9% photography-oriented tags
- 4% Immich memories

The three date ranges intentionally overlap. A photo from the last month can
enter through all three recent pools (and through a matching curated tag), a
six-month-old photo through the two wider pools, and a two-year-old photo only
through the widest. This creates a strong recency bias without imposing a hard
cutoff on older family and photography selections.

Outside the explicit recent-date pools, this remains a positive-source model:
an asset must enter through a selected Qwen tag, recognized person, or memory.
The recent pools deliberately admit any new asset that passes the hard
exclusions so recently captured photos are not hidden merely because tagging
lags behind ingestion. Known classes such as documents, screenshots, product
listings, electronics, DIY documentation, and timelapse frames remain excluded
from every source.

Two additional profiles are included:

```text
http://<NAS_LAN_IP>:3000/?curation_profile=family
http://<NAS_LAN_IP>:3000/?curation_profile=photography
```

Edit the YAML weights to change the algorithm—no image rebuild is required.
Kiosk watches the configuration file and refreshes connected clients.

## Image pipeline

The quality/performance combination is intentional:

```text
Immich original -> NAS Lanczos resize to 1920x1080 target -> prefetch/cache -> frame
```

`use_original_image: true` makes the NAS start with the best source. The DPR
patch reports the physical display dimensions, and `optimize_images: true`
prevents the 2 GB frame from decoding 20–40 MB camera originals. The UI is
fullscreen, with no browser chrome, videos, GIFs, or image effects.

The lower-left metadata shows the exact capture year and, when Immich has it,
the city and state. Assets without geographic metadata do not get an empty
location icon. `custom.css` adds a modest display-safe margin and extra spacing
around the metadata icons without using blur or other expensive effects.

## Build and deploy

The API key is never stored in this repository. On the NAS it is a Docker secret
at `secrets/immich_api_key`, read through `KIOSK_IMMICH_API_KEY_FILE`.

```sh
docker compose build immich-kiosk
docker compose up -d --no-build --force-recreate
docker logs immich-kiosk
```

Compose mounts `config/config.yaml` read-only at `/config` and `custom.css`
read-only at `/custom.css`. Updating the YAML is picked up by Kiosk's config
watcher; changing the CSS requires recreating the container so the mount is
present, followed by a browser refresh or its next reconnect.

The build fails if either local patch no longer applies to the pinned upstream
tag, or if the curation tests fail. The deployed image identifies itself as
`0.42.0-lenovo-curation1`.

## Device-specific notes

The tested hardware is a Lenovo CD-3L501F (`Walnut`) running stock AOSP Android
10 with 2 GB RAM and a 1920x1080 panel. Fully Kiosk Browser 1.61.2 is used as the
renderer.

Two MediaTek/Android problems mattered more than raw hardware performance:

- A failed Bluetooth-mouse pairing produced a continuous SystemUI bond retry
  loop. Bluetooth is kept off; a USB-C OTG mouse is the recovery input.
- MediaTek DuraSpeed suppressed Fully's child services. On this dedicated,
  always-powered kiosk, `setting.duraspeed.enabled` is set to `0`.

Wireless ADB works after `adb tcpip 5555`, but the locked stock build cannot make
that setting reboot-persistent. Do not treat wireless ADB as the device's normal
runtime dependency.

## Known next steps

- Add an easy per-slide “never show again” action without granting the display
  key write access to Immich.
- Add burst/near-duplicate suppression and optional per-person weights.
- Use human-reviewed contact sheets for expression and aesthetic quality; tags
  and face detection cannot reliably decide whether a person looks flattering.
- Re-evaluate the upstream pin deliberately rather than tracking `latest`.

See [docs/blog-outline.md](docs/blog-outline.md) for the implementation story and
verified measurements worth preserving for a future write-up.
