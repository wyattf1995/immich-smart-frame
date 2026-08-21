# Lenovo Smart Frame + Immich

[![CI](https://github.com/wyattf1995/immich-smart-frame/actions/workflows/validate.yml/badge.svg?branch=main)](https://github.com/wyattf1995/immich-smart-frame/actions/workflows/validate.yml)
[![License](https://img.shields.io/github/license/wyattf1995/immich-smart-frame)](./LICENSE)

Turn a discontinued Lenovo Smart Frame into a fullscreen, native-resolution
Immich display with a photo-selection algorithm you control.

This project keeps the frame deliberately simple: an Android kiosk browser
([Fully Kiosk Browser](https://www.fully-kiosk.com/)) renders the slideshow
while a Docker host runs a pinned, lightly patched build of
[Immich Kiosk](https://github.com/damongolding/immich-kiosk). It does not
modify Immich originals, ratings, albums, tags, or sidecars.

> [!IMPORTANT]
> This is an experimental community project, not a Lenovo, Immich, or Immich
> Kiosk product. It is tested on one Lenovo CD-3L501F running stock Android 10.

![Synthetic privacy-safe demo of the frame UI](docs/images/demo-frame.webp)

_Synthetic, privacy-safe demo image. It does not contain a real family photo or
live deployment metadata._

## Why this exists

The original Lenovo service was limited to coarse album-based playback and was
eventually discontinued. A replacement should do more than reproduce that
experience. This one adds:

- a true fullscreen browser client;
- physical-pixel image requests on high-density Android WebViews;
- server-side resizing, caching, and prefetch for low-memory hardware;
- named, explicitly weighted curation profiles;
- recency ladders, Immich memories, people, albums, tags, and date ranges;
- exact capture year and optional city/state metadata;
- reviewable patches against a pinned upstream release.

## Architecture

```text
Immich originals
      |
      v
Docker host: patched Immich Kiosk
  - weighted source selection
  - original fetch + Lanczos resize
  - cache and prefetch
      |
      v
Android frame: Fully Kiosk Browser
  - fullscreen WebView
  - 1920x1080 physical-pixel request
```

The frame never receives an Immich administrator credential. Use a dedicated,
read-only API key and keep it in the Compose secret described below.

## Tested stack

| Component | Tested version |
| --- | --- |
| Lenovo frame | CD-3L501F / `Walnut`, 1920x1080, 2 GB RAM |
| Frame OS | Stock Android 10 |
| Browser | Fully Kiosk Browser 1.61.2 |
| Immich | 3.0.3 |
| Immich Kiosk base | 0.42.0 |
| Docker | Compose v2 |

Other Android displays may work, especially when their browser reports a CSS
viewport smaller than the physical panel, but they have not been verified.

## Quick start

Prerequisites:

- a working Immich server;
- a Docker host that can reach Immich;
- Docker Engine with Compose v2;
- an Android browser or kiosk browser that can reach the Docker host.

Clone the repository and create local deployment files:

```sh
git clone https://github.com/wyattf1995/immich-smart-frame.git
cd immich-smart-frame
cp .env.example .env
cp config/config.example.yaml config/config.yaml
mkdir -p secrets
read -r -s -p "Enter the read-only Immich API key: " IMMICH_API_KEY
printf '\n'
printf '%s' "$IMMICH_API_KEY" > secrets/immich_api_key
unset IMMICH_API_KEY
chmod 600 .env secrets/immich_api_key
```

Edit `.env` and set at least `IMMICH_URL` and `TZ`. Neither `.env`, the active
configuration, nor `secrets/` is tracked by Git.

Build and start the service:

```sh
docker compose build immich-kiosk
docker compose up -d
docker compose ps
```

Open this URL on the frame, replacing the host name with the Docker host:

```text
http://docker-host.local:3000/
```

The starter profile requires no custom tags or albums. It favors the last 30,
180, and 730 days while retaining an all-time path and Immich memories.

### API-key permissions

In Immich Web, create a dedicated API key from the user settings panel and
enable only the read permissions required by your active sources. The tested
starter configuration uses:

- asset download, read, and view;
- album read and statistics;
- archive read;
- face, memory, person, tag, and user read.

Do not give the display key write permissions. `asset.download` is needed
because Kiosk starts with the original before resizing it on the Docker host.
See [API-key permissions](docs/api-key-permissions.md) for the exact permission
set and when each toggle is needed.

## Curation profiles

The custom image accepts named profiles composed of these source types:

- `album`
- `person`
- `tag`
- `date`
- `memories`
- `random`

Weights are direct relative probabilities. They do not have to total 100, but
using 100 makes a profile easy to reason about.

The generic [starter configuration](config/config.example.yaml) works without
custom tags. [qwen.example.yaml](config/qwen.example.yaml) preserves the more
advanced Qwen-tagged family/dog/travel/photography mix used by the original
deployment. Referenced tags must already exist in Immich before selecting that
profile.

Different displays can select different profiles without rebuilding:

```text
http://docker-host.local:3000/?curation_profile=family
http://docker-host.local:3000/?curation_profile=photography
```

See [Curation](docs/curation.md) for weighting behavior, recency overlap, and
the tradeoff between fresh untagged photos and strict positive-source filtering.

## Native-resolution image path

The Lenovo panel is physically 1920x1080, but its Android WebView reports a
960x540 CSS viewport at device-pixel ratio 2. Upstream Kiosk therefore prepared
a 960x540 image that Android stretched back to the panel size.

[fully-kiosk-dpr.patch](custom-image/fully-kiosk-dpr.patch) multiplies the
fallback viewport by `window.devicePixelRatio`. With `use_original_image` and
`optimize_images` enabled, the resulting path is:

```text
Immich original -> Docker-host Lanczos resize -> 1920x1080 target -> frame
```

This avoids both visible 2x upscaling and sending full camera originals to a
2 GB Android device.

## What the custom image changes

The Dockerfile pins Immich Kiosk `v0.42.0` and applies three files:

1. `fully-kiosk-dpr.patch` requests physical pixels from ordinary WebViews.
2. `weighted-curation.patch` adds named, directly weighted source profiles.
3. `weighted-curation-tests.patch` guards profile lookup and exact weights.

The build checks that every patch still applies, runs the targeted upstream Go
test packages, then compiles the binary. Upstream is intentionally pinned;
upgrades should be reviewed rather than following `latest`.

## Frame setup

The Lenovo frame has no touchscreen. Initial provisioning uses a USB-C OTG
mouse, Android Developer Options, one USB-debugging authorization, the official
[Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools),
and a kiosk-browser APK obtained from the browser publisher. Wireless ADB can
simplify setup, but it does not survive reboot on the locked stock firmware and
should never be left enabled on a general-purpose LAN.

See [Device setup](docs/device-setup.md) and
[Troubleshooting](docs/troubleshooting.md) for the exact process and the two
MediaTek-specific service failures encountered during testing. The device guide
also covers VLAN and firewall isolation for a dedicated Android 10 frame.

## Validation

After setup, run:

```sh
./scripts/validate.sh
```

The validator checks public-repository hygiene, configuration/profile weights,
Compose rendering, patch applicability, and the patched Go tests. GitHub Actions
runs the same checks for pushes and pull requests. Use
`./scripts/validate.sh --static` to skip the Docker image build.

## Security

- Keep Immich and Kiosk on a trusted LAN or protect them with an authenticated
  reverse proxy.
- Never commit `.env`, `config/config.yaml`, or `secrets/`.
- Use a dedicated read-only Immich key.
- Treat wireless ADB as privileged shell access and disable it after
  provisioning.
- Do not publish screenshots containing personal photos by default.

See [SECURITY.md](SECURITY.md) for reporting and deployment guidance and
[Device setup](docs/device-setup.md#lock-down-the-frame-network) for Android
network isolation.

## API permissions and hardening

Grant only the permissions required by the active sources. This project is
designed to work with these minimum API rights:

- `asset.download`
- `asset.read`
- `asset.view`
- `album.read`
- `album.statistics`
- `archive.read`
- `face.read`
- `memory.read`
- `person.read`
- `tag.read`
- `user.read`

Do not grant write or delete permissions to the display key. Keep wireless ADB
disabled outside provisioning windows, and disconnect after setup is complete.

The frame section should be isolated from general LAN trust boundaries where
possible (for example, via a dedicated VLAN and firewall allowlist).

## Release and version policy

Tagged releases follow [Semantic Versioning](https://semver.org/). Before
`1.0.0`, configuration and deployment behavior may still change faster, but any
breaking change will be called out in the changelog and release notes.

Use repository tags, not arbitrary upstream versions, for upgrades and
rollbacks. Each tag identifies the exact upstream Immich Kiosk pin, local
patches, and documentation state that were validated together.

See [CHANGELOG.md](CHANGELOG.md), [Upgrade and rollback](docs/upgrade-rollback.md),
and [GOVERNANCE.md](GOVERNANCE.md) for the release process and support
expectations.

## Project status

The core slideshow, native-resolution path, weighted profiles, recency bias,
year/location metadata, and low-memory performance tuning are working. Known
future work includes persistent per-display “already shown” history,
near-duplicate suppression, and a read-only-display-safe rejection workflow.

## License and upstream

This project modifies and builds Immich Kiosk, which is licensed under the GNU
Affero General Public License v3.0. This repository is distributed under the
same license. Contributions are accepted on an inbound-equals-outbound basis:
by submitting code or documentation, you agree that it is licensed under this
repository's AGPL-3.0 terms.

See [LICENSE](LICENSE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and
[CONTRIBUTING.md](CONTRIBUTING.md).

The upstream project is not responsible for these device-specific patches or
support requests. Please reproduce problems against upstream before filing an
upstream issue.
