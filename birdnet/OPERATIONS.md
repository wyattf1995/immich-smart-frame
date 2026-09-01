# BirdNET-Go operations

This directory is a reproducible deployment package, not the source of truth
for live state. It contains no credentials; verify the running containers and
host-side files before claiming a particular Unraid deployment is current.

## First setup on Unraid

1. Keep the Compose project in `/boot/config/birdnet-go/` so Unraid preserves
   it across reboots. Mutable application state belongs in
   `/mnt/user/appdata/birdnet-go/`, not on the boot flash.
2. Copy the complete contents of this `birdnet/` package into
   `/boot/config/birdnet-go/`, including `frame-view/` and `tests/`. Validate
   that `frame-view/index.html` and `frame-view/nginx.conf` are regular files
   before starting; a missing bind source can otherwise become a directory.
   Unraid's FAT boot filesystem exposes those copies as root-only inside the
   UID 101 container, so install readable runtime copies into appdata:

   ```sh
   install -d -m 0755 /mnt/user/appdata/birdnet-go/frame-view
   install -m 0644 frame-view/index.html frame-view/nginx.conf \
     /mnt/user/appdata/birdnet-go/frame-view/
   ```
3. Copy `config/config.yaml` once to
   `/mnt/user/appdata/birdnet-go/config/config.yaml`, then create an empty
   `/mnt/user/appdata/birdnet-go/data/` directory. The tracked file remains a
   credential-free template; the appdata copy becomes private after adding an
   audio source.
4. Create `.env` from `.env.example` in `/boot/config/birdnet-go/`. Set
   `BIRDNET_BIND_IP` to the Unraid LAN address. Do not use `0.0.0.0`; the
   compose file fails if this value is omitted but cannot validate the address
   class. Keep the two appdata paths unchanged unless the storage design
   changes deliberately.
5. Ensure the appdata `config/` and `data/` directories are writable by the configured
   `BIRDNET_UID:BIRDNET_GID` before starting. This compose file intentionally
   runs as that unprivileged ID, so the container cannot repair host ownership.
6. Validate and start from `/boot/config/birdnet-go/`:

   ```sh
   docker compose --env-file .env -f docker-compose.yaml config --quiet
   docker compose --env-file .env -f docker-compose.yaml up -d
   docker compose --env-file .env -f docker-compose.yaml ps
   ```

   Open `http://<BIRDNET_BIND_IP>:<WEB_PORT>` from the LAN for BirdNET-Go's full
   dashboard. The frame-specific view is at
   `http://<BIRDNET_BIND_IP>:<FRAME_VIEW_PORT>/`. The frame-sidecar health check
   includes BirdNET-Go's public health response, but it does not prove that a
   microphone is producing usable audio. Check the page's audio state and
   BirdNET-Go's System Health page after adding a source.

## Add the audio source

The optional `nest-audio-bridge` turns one Home Assistant Nest WebRTC session
into a private RTSP Opus audio endpoint. Home Assistant remains responsible for
Nest OAuth and session renewal; the bridge does not need raw Google client or
refresh-token credentials. The pinned go2rtc release is patched for Home
Assistant's current subscription signaling so the websocket stays open while
the camera is in use.

Use Backyard first. Its live entity is
`camera.backyard_backyard_camera`. Garage
(`camera.garage_garage_camera`) is a manual fallback only; never configure both
at once. Nest still sends its required video, audio, and data tracks from the
cloud, even though only audio leaves the bridge for BirdNET.

### Prepare the Home Assistant credential

Create a dedicated non-admin Home Assistant user named `BirdNET Camera Bridge`,
log into a separate browser session as that user, and create one long-lived
access token from its profile. Home Assistant tokens are not camera-scoped: a
non-admin token avoids administrative access but can still have broad entity
access. Do not reuse the owner token unless that tradeoff is explicitly chosen.

On Unraid, capture the token without putting it in shell history, then make the
file readable only by the UID that runs the bridge:

```sh
install -d -m 0700 -o 99 -g 100 /mnt/user/appdata/birdnet-go/secrets
install -m 0400 -o 99 -g 100 /dev/null \
  /mnt/user/appdata/birdnet-go/secrets/HA_TOKEN
read -rsp 'Home Assistant token: ' BIRDNET_HA_TOKEN
printf '%s' "$BIRDNET_HA_TOKEN" > /mnt/user/appdata/birdnet-go/secrets/HA_TOKEN
unset BIRDNET_HA_TOKEN
```

Set the private `.env` values; `HA_HOST` is `host[:port]` without a URL scheme:

```dotenv
HA_HOST=<HA_LAN_IP>
HA_CAMERA_ENTITY=camera.backyard_backyard_camera
HA_TOKEN_FILE=/mnt/user/appdata/birdnet-go/secrets/HA_TOKEN
```

The token is a mounted Compose secret. It is never passed through an
environment variable, command argument, go2rtc API, or published port. Revoke
it in Home Assistant immediately if the file or container boundary is exposed.

### Build and make one controlled connection

Building and starting the idle bridge does not open a camera; go2rtc creates
the source lazily when an RTSP client connects:

```sh
docker compose --env-file .env -f docker-compose.yaml \
  --profile nest-audio build nest-audio-bridge
docker compose --env-file .env -f docker-compose.yaml \
  --profile nest-audio up -d --no-deps nest-audio-bridge
docker compose --env-file .env -f docker-compose.yaml \
  --profile nest-audio up -d --no-deps --force-recreate birdnet-go
```

The following 30-second probe is the first camera connection. Make a brief,
ordinary sound in the Backyard camera's pickup area during the sample. It
decodes audio to the null sink, so no household audio is retained:

```sh
docker exec birdnet-go ffmpeg -nostdin -hide_banner -loglevel info \
  -rtsp_transport tcp -allowed_media_types audio \
  -i 'rtsp://nest-audio-bridge:8554/nest_camera?audio=opus' \
  -map 0:a:0 -t 30 -af volumedetect -f null -
```

The input must report `opus`, `48000 Hz`, and the camera's channel count.
`mean_volume` and `max_volume` must rise above digital silence during the test
sound. BirdNET then uses its own FFmpeg path to decode, resample to 48 kHz when
needed, and downmix to mono. A valid codec declaration alone proves negotiation,
not that the microphone has useful acoustic signal.

If the probe passes, edit the private appdata `config.yaml` and replace
`realtime.rtsp.streams: []` with:

```yaml
streams:
  - name: Nest Backyard
    url: rtsp://nest-audio-bridge:8554/nest_camera?audio=opus
    enabled: true
    type: rtsp
    transport: tcp
    mediaMode: audio-only
    channelMode: downmix
    models: [birdnet]
transport: tcp
```

Keep `transport: tcp` at the `realtime.rtsp` level: BirdNET-Go 20260823 uses
the global value in its FFmpeg engine. Then validate and restart:

```sh
docker compose --env-file .env -f docker-compose.yaml config --quiet
docker compose --env-file .env -f docker-compose.yaml \
  --profile nest-audio up -d nest-audio-bridge birdnet-go
docker exec birdnet-go curl -fsS \
  http://127.0.0.1:8080/api/v2/streams/health
```

Require a 12-minute minimum soak—longer than two normal five-minute Nest session
windows—before calling the bridge stable. During the soak, confirm the same
BirdNET source remains healthy and that the bridge log has no new producer,
authentication, websocket, ICE, or FFmpeg failures:

```sh
docker compose --env-file .env -f docker-compose.yaml \
  --profile nest-audio logs --since 15m nest-audio-bridge birdnet-go
docker exec birdnet-go curl -fsS \
  http://127.0.0.1:8080/api/v2/streams/health
docker exec birdnet-go curl -fsS \
  http://127.0.0.1:8080/api/v2/health/audio
```

If Backyard is acoustically unusable, stop BirdNET first so its active HA
subscription closes, change only `HA_CAMERA_ENTITY` to the Garage entity,
recreate the bridge, and repeat the controlled probe and soak. This avoids two
simultaneous cloud sessions and reconnect storms against Google's camera quota.

The in-app RTSP health monitor is configured for 60 seconds of data and a
30-second check interval. A local RTSP or USB microphone is still the preferred
fallback if the cloud path cannot remain healthy through the soak.

The tracked dashboard configuration enables compact species images for recent
detections and the summary. It prefers AviCommons and falls back across the
other providers supported by BirdNET-Go. Keep the upstream attribution visible;
do not copy remote images into this repository.

## Frame integration

FrameOS should load the custom LAN view at
`http://<BIRDNET_BIND_IP>:<FRAME_VIEW_PORT>/`, not the administration dashboard.
The sidecar presents a bounded 1920x1080 layout with the latest detection,
today's six most active species, four recent calls, real species images, and
image attribution. It distinguishes a healthy detector with no audio source
from an empty listening station and a backend outage. Species images can return
503 while BirdNET-Go resolves a cold provider result; the page preserves its
fallback and retries later instead of blocking the layout.

The sidecar only proxies the public read endpoints its page uses. It does not
forward arbitrary API routes, mutations, credentials, or CSRF tokens. Both the
BirdNET-Go dashboard and frame view remain LAN-only. Do not expose either to the
public internet or add a Cloudflare tunnel without enabling authentication and
reviewing the reverse-proxy trust settings.

## Retention, privacy, and watchdog

The tracked configuration exports audio clips as Opus and deletes them by age
after 30 days. It intentionally has no `maxusage` fallback: the retention
deadline is deterministic rather than dependent on free space. Clips can contain
household audio, so restrict appdata and backups to trusted administrators; do
not copy clips into this repository, ticket, or public storage.

`scripts/birdnet-watchdog.sh` is a reporting-only host script. It requests
`/api/v2/health/audio`, requires every reported source to be `HEALTHY`, and
requires a recent `last_dispatch` timestamp or explicit age measurement. It
reports Docker `RestartCount` for `birdnet-go` and `nest-audio-bridge`, and
checks free space for the persistent data directory. It never writes files or
restarts/recreates containers. Run it from an Unraid User Script or monitoring
system and treat a nonzero exit status as an operator alert:

```sh
BIRDNET_BIND_IP=<Unraid_LAN_IP> \
WEB_PORT=8090 \
BIRDNET_DATA_DIR=/mnt/user/appdata/birdnet-go/data \
./scripts/birdnet-watchdog.sh
```

The bridge is optional; its absent container is reported as `not-created` and
does not make the watchdog fail. When audio monitoring is expected, a missing
or stale audio-health result is a failure. Tune the conservative freshness
window only when justified by the configured source interval:

```sh
WEB_PORT=8090 BIRDNET_AUDIO_MAX_AGE_SECONDS=120 ./scripts/birdnet-watchdog.sh
```

### Reboot recovery status

**UNVERIFIED: full Unraid reboot recovery remains UNVERIFIED until physically tested.** Before treating recovery as proven, reboot the actual
server during a maintenance window, confirm the array/appdata mounts return,
then confirm BirdNET-Go, the optional bridge, audio freshness, and the frame
view recover without manual intervention. Record the physical test date and
results with the deployment notes. Do not infer recovery from Compose startup
or a container restart alone.

## Backups

Stop the service briefly for a consistent filesystem-level backup, or use
BirdNET-Go's built-in database backup for the database-specific artifact. Keep
both `config/` and `data/` because models, clips, and settings are persistent:

```sh
docker compose --env-file .env -f docker-compose.yaml stop birdnet-go
tar --xattrs --acls -czf /path/to/protected-backups/birdnet-$(date +%Y%m%d-%H%M%S).tgz \
  -C /mnt/user/appdata/birdnet-go config data
docker compose --env-file .env -f docker-compose.yaml start birdnet-go
```

Backups may contain RTSP credentials after setup. Store them in the protected
Unraid backup destination and never commit or paste them into an issue.

Before restoring a backup, stop BirdNET-Go, preserve the current `config/` and
`data/` directories as a separate rollback copy, restore both directories with
their original ownership, then start and verify the audio-health endpoint. A
backup is useful only if its restore procedure has been rehearsed on the target
host; that rehearsal is separate from the UNVERIFIED full-reboot recovery test.

## Upgrade and rollback

The compose image is pinned to the 20260823 release manifest. To upgrade,
review the upstream release and registry digest, change only the image line to
the new release tag plus its complete `sha256:` digest, run the validation test,
refresh the appdata copies, then pull and recreate:

```sh
./tests/test-birdnet-compose.sh
./tests/test-frame-view.sh
install -m 0644 frame-view/index.html frame-view/nginx.conf \
  /mnt/user/appdata/birdnet-go/frame-view/
docker compose --env-file .env -f docker-compose.yaml pull birdnet-go birdnet-frame-view
docker compose --env-file .env -f docker-compose.yaml up -d --force-recreate birdnet-go birdnet-frame-view
```

Record the previous image line before upgrading. To roll back, restore that
known-good pinned line, validate, and run the same pull/up commands. Never use
`:latest`, `:nightly`, or an unpinned digest in production. Back up `config/`
and `data/` first because a newer release may migrate the database; follow the
upstream release notes for any migration-specific rollback warning.

To remove only the optional frame view without touching BirdNET-Go, run:

```sh
docker compose --env-file .env -f docker-compose.yaml stop birdnet-frame-view
docker compose --env-file .env -f docker-compose.yaml rm -f birdnet-frame-view
```

## Official references

- [BirdNET-Go repository](https://github.com/tphakala/birdnet-go)
- [Installation and Docker guidance](https://github.com/tphakala/birdnet-go/wiki/installation)
- [Docker Compose guide](https://github.com/tphakala/birdnet-go/wiki/docker_compose_guide.md)
- [RTSP troubleshooting](https://github.com/tphakala/birdnet-go/wiki/rtsp-troubleshooting)
- [Security guidance](https://github.com/tphakala/birdnet-go/wiki/security)
- [20260823 release](https://github.com/tphakala/birdnet-go/releases/tag/20260823)
- [Pinned image manifest on GHCR](https://ghcr.io/tphakala/birdnet-go:20260823)
- [go2rtc v1.9.14 source](https://github.com/AlexxIT/go2rtc/tree/v1.9.14)
- [Home Assistant 2026.8.3 WebRTC subscription implementation](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/camera/webrtc.py)
- [Home Assistant 2026.8.3 Nest session renewal](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/nest/camera.py)
- [Google Camera Live Stream trait](https://developers.google.com/nest/device-access/traits/device/camera-live-stream)
