# Lenovo Smart Frame + Immich

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
- per-profile album penalties for soft de-ranking without exclusion;
- exact capture date and optional city/state metadata, with redundant slide
  dates hidden when a visible album title starts with the same date;
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
| This project | 0.1.0 |
| Docker | Compose v2 |

Other Android displays may work, especially when their browser reports a CSS
viewport smaller than the physical panel, but they have not been verified.

## Is this for my display?

| Your setup | Fit and next step |
| --- | --- |
| Lenovo CD-3L501F on stock Android 10, with a working Immich server and Docker host | This is the tested setup. Start with the browser slideshow below, then follow [Device setup](docs/device-setup.md). |
| Another Android display that can open a LAN web page | The browser slideshow may work, but hardware compatibility is unverified. Check image size, browser memory use, and reconnect behavior before leaving it unattended. |
| A Lenovo frame with no touchscreen or authorized USB debugging | Provisioning needs a USB-C OTG mouse and the steps in [Device setup](docs/device-setup.md). The slideshow does not require persistent wireless ADB. |
| Immich plus Home Assistant on the same display | Start with Photos, then consider the optional [FrameOS companion](#optional-frameos-and-home-assistant-companion). Home Assistant is not required for photos. |
| No Immich server or Docker host, or a plug-and-play cloud photo frame | This project does not provide those services. It requires a self-hosted setup and ongoing maintenance. |

The first milestone is a working slideshow in a browser on your LAN. Device
provisioning, custom curation, and the optional Home Assistant companion are
separate steps; setup time depends on the hardware and services you already have.

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
install -d -m 700 secrets
install -m 600 /dev/null secrets/immich_api_key
install -d -m 700 -o 65532 -g 65532 offline-assets
./scripts/check-offline-assets-permissions.sh
${EDITOR:-vi} secrets/immich_api_key
chmod 600 .env
```

Edit `.env` and set at least `IMMICH_URL` and `TZ`. Neither `.env`, the active
configuration, nor `secrets/` is tracked by Git. Paste only the API-key value
into the secret file—without quotes—and save it. This editor-based flow keeps
the key out of shell history.

Build and start the service:

```sh
docker compose build immich-kiosk
docker compose up -d --wait --wait-timeout 120
docker compose ps
```

Open this URL on the frame, replacing the host name with the Docker host:

```text
http://docker-host.local:3000/
```

The starter profile requires no custom tags or albums. It favors the last 30,
180, and 730 days while retaining an all-time path and Immich memories.

Before moving to the frame, confirm that a photo loads from another LAN device
and that `docker compose ps` reports the service as healthy. Then verify the
frame's physical image size and reconnect behavior using
[Validation](#validation).

If that first check fails, jump to the relevant troubleshooting section:

- [Black screen or permanent loading indicator](docs/troubleshooting.md#black-screen-or-permanent-loading-indicator)
- [Soft or low-resolution images](docs/troubleshooting.md#the-image-looks-soft)
- [A profile returns an error](docs/troubleshooting.md#a-profile-returns-an-error)
- [Offline/reconnect indicator](docs/troubleshooting.md#flower-icon-with-a-red-slash)
- [Wireless ADB disappeared after reboot](docs/troubleshooting.md#wireless-adb-disappeared)

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

The Dockerfile pins the exact Immich Kiosk `v0.42.0` commit and applies an
ordered, reviewable patch stack. Its main implementation layers are:

1. `fully-kiosk-dpr.patch` requests physical pixels from ordinary WebViews.
2. `weighted-curation.patch` adds named, directly weighted source profiles.
3. `runtime-hardening.patch` updates vulnerable Go dependencies, bounds client
   image dimensions, and normalizes curation input.
4. `album-penalties.patch` adds validated, profile-specific soft de-ranking.
5. The backend performance patches make album filtering and date pools linear
   and bounded, cache memories availability briefly, and cap concurrent
   prefetch.
6. The resilience patches add bounded dependency handling, readiness/liveness,
   graceful shutdown, and slideshow recovery.
7. The cache-hardening patches version browser state, scope the durable offline
   pool, invalidate derived caches after mutations, and reject stale async
   refills.
8. Separate regression-test patches guard each behavior before its matching
   implementation patch is applied.

The build asserts the tag's expected commit, checks that every patch still
applies, runs the complete upstream Go test suite, then compiles the binary.
Upgrades are reviewed rather than following `latest`.

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

## Optional FrameOS and Home Assistant companion

FrameOS can host the slideshow and Home Assistant in one full-screen Android
app built for the no-touch Lenovo frame. It keeps separate warm Gecko sessions
for Photos and Home Assistant, adds a native cached Weather view with subtle
condition-driven animation, and routes Home, Cameras, and Calendar through one
same-origin iframe without browser chrome or repeated tab loads.

The [Home Assistant wall-panel guide](docs/home-assistant-wall-panel.md)
includes the privacy-safe dashboard and local wrapper. The protected
[FrameOS router](docs/frame-mode-router.md) turns two repeatable OEM gestures
into the circular Photos/Home/Weather/Cameras/Calendar control and documents
contextual volume/star behavior, deployment, verification, and legacy
Fully-plus-Firefox rollback. Home Assistant remains optional for the slideshow.

## Validation

After setup, run:

```sh
./scripts/validate.sh
```

The validator checks public-repository hygiene, configuration/profile weights,
the Home Assistant companion examples, Compose rendering, patch applicability,
and the patched Go tests. `scripts/deployment-input-snapshot.sh` creates and
verifies a protected, versioned snapshot of the ignored environment, active
config, API-key file, and offline assets before an upgrade or rollback.
`scripts/check-frame-readiness.sh` is a read-only, cron-friendly monitor hook;
it reports Kiosk liveness and dependency readiness separately and never restarts
a service. This repository has no GitHub Actions workflows: repository Actions
are disabled, and all validation and release gates run locally. The local
validator rejects workflow files and Dependabot version-update configuration
because both can launch GitHub-hosted jobs. Use `./scripts/validate.sh --static`
to skip the Docker image build.
The offline bind mount is private writable state for the image's non-root
UID/GID 65532; rerun `scripts/check-offline-assets-permissions.sh` after moving
the deployment or restoring a snapshot. See [cache operations](docs/cache-operations.md)
for cache invalidation and upgrade migration details.

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

## Release and version policy

The published [v0.1.0 release](https://github.com/wyattf1995/immich-smart-frame/releases/tag/v0.1.0)
is the initial release. The default branch also contains later work listed in
[`Unreleased`](CHANGELOG.md#unreleased); its documentation and optional FrameOS
features should not be assumed to describe that older tag. Check the source
revision and matching release notes before deploying or rolling back.

Tagged releases follow [Semantic Versioning](https://semver.org/). Before
`1.0.0`, configuration and deployment behavior may still change faster, but any
breaking change will be called out in the changelog and release notes.

Use repository tags, not arbitrary upstream versions, for upgrades and
rollbacks. Each tag identifies the exact upstream Immich Kiosk pin, local
patches, and documentation state that were validated together.

See [CHANGELOG.md](CHANGELOG.md), [Upgrade and rollback](docs/upgrade-rollback.md),
[Maintainer releases](docs/releasing.md), [Resilience operations](docs/resilience-operations.md), and [GOVERNANCE.md](GOVERNANCE.md)
for the release process and support expectations.

## Project status

The core slideshow, native-resolution path, weighted profiles, recency bias,
exact-date/location metadata, and low-memory performance tuning are working. Known
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
The Compose defaults cap the kiosk at three CPUs and 1 GiB RAM, which leaves
headroom for the NAS-hosted kiosk. Tune `KIOSK_CPUS` and
`KIOSK_MEMORY_LIMIT` only after observing steady-state memory headroom.
