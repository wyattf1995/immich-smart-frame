# BirdNET-Go operations

This directory is a deployment package, not a deployment record. It does not
contain credentials and it has not been deployed to Unraid.

## First setup on Unraid

1. Keep the Compose project in `/boot/config/birdnet-go/` so Unraid preserves
   it across reboots. Mutable application state belongs in
   `/mnt/user/appdata/birdnet-go/`, not on the boot flash.
2. Copy `config/config.yaml` once to
   `/mnt/user/appdata/birdnet-go/config/config.yaml`, then create an empty
   `/mnt/user/appdata/birdnet-go/data/` directory. The tracked file remains a
   credential-free template; the appdata copy becomes private after adding an
   audio source.
3. Create `.env` from `.env.example` in `/boot/config/birdnet-go/`. Set
   `BIRDNET_BIND_IP` to the Unraid LAN address. Do not use `0.0.0.0`; the
   compose file intentionally fails if this value is omitted. Keep the two
   appdata paths unchanged unless the storage design changes deliberately.
4. Ensure the appdata `config/` and `data/` directories are writable by the configured
   `BIRDNET_UID:BIRDNET_GID` before starting. This compose file intentionally
   runs as that unprivileged ID, so the container cannot repair host ownership.
5. Validate and start from `/boot/config/birdnet-go/`:

   ```sh
   docker compose --env-file .env -f docker-compose.yaml config --quiet
   docker compose --env-file .env -f docker-compose.yaml up -d
   docker compose --env-file .env -f docker-compose.yaml ps
   ```

   Open `http://<BIRDNET_BIND_IP>:<WEB_PORT>` from the LAN. A healthy container
   only proves that the web process is serving; check BirdNET-Go's System Health
   page separately after adding audio.

## Add the audio source

The current Nest cameras expose cloud WebRTC sessions rather than dependable
local RTSP. Do not put a Nest WebRTC URL in this file. Use a dedicated local
RTSP microphone or another local RTSP audio source. Edit `config/config.yaml`
in the private appdata copy, set `realtime.rtsp.streams` to one or more sources, and keep the URL's
credentials out of commits and screenshots. The sample uses the RFC 5737
documentation address `192.0.2.20`, which must be replaced before use.

After editing, validate the compose file and restart the service:

```sh
docker compose --env-file .env -f docker-compose.yaml config --quiet
docker compose --env-file .env -f docker-compose.yaml restart birdnet-go
```

The in-app RTSP health monitor is configured for 60 seconds of data and a
30-second check interval. Use the BirdNET-Go RTSP troubleshooting page when a
source is repeatedly unhealthy.

## Frame integration

Once the source is producing detections, the FrameOS Birds view can load the
LAN dashboard at `http://<BIRDNET_BIND_IP>:<WEB_PORT>`. That integration is
intentionally outside this package. Do not expose this dashboard to the public
internet or add a Cloudflare tunnel without enabling BirdNET-Go authentication
and reviewing the reverse-proxy trust settings.

## Backups

Stop the service briefly for a consistent filesystem-level backup, or use
BirdNET-Go's built-in database backup for the database-specific artifact. Keep
both `config/` and `data/` because models, clips, and settings are persistent:

```sh
docker compose --env-file .env -f docker-compose.yaml stop birdnet-go
tar --xattrs --acls -czf /path/to/protected-backups/birdnet-$(date +%Y%m%d-%H%M%S).tgz config data
docker compose --env-file .env -f docker-compose.yaml start birdnet-go
```

Backups may contain RTSP credentials after setup. Store them in the protected
Unraid backup destination and never commit or paste them into an issue.

## Upgrade and rollback

The compose image is pinned to the 20260823 release manifest. To upgrade,
review the upstream release and registry digest, change only the image line to
the new release tag plus its complete `sha256:` digest, run the validation test,
then pull and recreate:

```sh
./tests/test-birdnet-compose.sh
docker compose --env-file .env -f docker-compose.yaml pull birdnet-go
docker compose --env-file .env -f docker-compose.yaml up -d --force-recreate birdnet-go
```

Record the previous image line before upgrading. To roll back, restore that
known-good pinned line, validate, and run the same pull/up commands. Never use
`:latest`, `:nightly`, or an unpinned digest in production. Back up `config/`
and `data/` first because a newer release may migrate the database; follow the
upstream release notes for any migration-specific rollback warning.

## Official references

- [BirdNET-Go repository](https://github.com/tphakala/birdnet-go)
- [Installation and Docker guidance](https://github.com/tphakala/birdnet-go/wiki/installation)
- [Docker Compose guide](https://github.com/tphakala/birdnet-go/wiki/docker_compose_guide.md)
- [RTSP troubleshooting](https://github.com/tphakala/birdnet-go/wiki/rtsp-troubleshooting)
- [Security guidance](https://github.com/tphakala/birdnet-go/wiki/security)
- [20260823 release](https://github.com/tphakala/birdnet-go/releases/tag/20260823)
- [Pinned image manifest on GHCR](https://ghcr.io/tphakala/birdnet-go:20260823)
