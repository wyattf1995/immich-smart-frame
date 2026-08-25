# Upgrade and rollback

This project pins an upstream Immich Kiosk release and carries local patches on
top. Upgrade and rollback should therefore happen at the repository-tag level,
not by changing one container image in isolation.

## Before you upgrade

1. Fetch the latest tags from this repository.
2. Read the release notes for the target repository tag and its pinned upstream
   Immich Kiosk release.
3. Review local changes in `.env`, `config/config.yaml`, and any reverse-proxy
   or network assumptions.
4. Create a protected snapshot of those deployment inputs before changing a
   revision. The snapshot includes the ignored API-key file and offline assets,
   so place it outside the checkout with mode 700 or stronger.
5. Run `./scripts/validate.sh` on the target revision before replacing a
   working display.

## Upgrade flow

```sh
SNAPSHOT_DIR=/protected/backups/immich-smart-frame/$(date -u +%Y%m%dT%H%M%SZ)-before-TAG_OR_BRANCH
./scripts/deployment-input-snapshot.sh create "$SNAPSHOT_DIR" .env
git fetch --tags
git checkout TAG_OR_BRANCH
./scripts/deployment-input-snapshot.sh verify "$SNAPSHOT_DIR" .env
docker compose build immich-kiosk
docker compose up -d --wait --wait-timeout 120 immich-kiosk
docker compose ps
```

Then verify:

- the slideshow loads from another device on the LAN;
- the frame renders a full-resolution image;
- the active curation profile still works;
- logs show no patch-application or startup failures.

`docker compose --wait` gates only local Kiosk liveness. It deliberately does
not treat an Immich dependency outage as a reason to restart Kiosk. Run the
read-only readiness sampler after rollout and from the host scheduler:

```sh
./scripts/check-frame-readiness.sh
```

It invokes Kiosk's local `--livecheck` and dependency-aware `--readycheck`
separately. Add the optional array, Home Assistant, and ADB hooks described in
[Resilience operations](resilience-operations.md) for a full-stack sample.

If the deployment also uses FrameOS, stage the signed release APK and router
separately. Verify the APK signature and install it with `adb install -r` using
the same signing certificate so configuration and encrypted sessions remain
intact. Exercise Photos, Home, Weather, Cameras, and Calendar through a staged
router path before renaming it into place. Keep the prior APK, router, and
private config under distinct known-good names off the frame.

## Rollback flow

If the new revision fails, roll back to the last known-good repository tag:

```sh
git fetch --tags
git checkout LAST_KNOWN_GOOD_TAG
./scripts/deployment-input-snapshot.sh restore \
  /protected/backups/immich-smart-frame/KNOWN_GOOD_SNAPSHOT .env --confirm-restore
docker compose build immich-kiosk
docker compose up -d --wait --wait-timeout 120 immich-kiosk
docker compose ps
./scripts/deployment-input-snapshot.sh verify \
  /protected/backups/immich-smart-frame/KNOWN_GOOD_SNAPSHOT .env
```

Rollback is safe only when code and its matching versioned input snapshot move
together. Leaving `.env`, `config/config.yaml`, `secrets/immich_api_key`, or
`offline-assets/` at their newer values is preservation, not rollback. Restore
uses an explicit confirmation because it overwrites those private inputs and
removes offline assets absent from the known-good snapshot.

FrameOS rollback is independent of the container rollback. Disable its two
gesture rules, restore the verified legacy router/config pair with blank
`FRAMEOS_*` settings, and confirm that Fully resumes Photos and Firefox resumes
Home Assistant. Do not clear app data: that is not required to disable FrameOS
and would remove authentication and recovery state.

## Versioning expectations

- `MAJOR`: incompatible configuration or operational changes.
- `MINOR`: backward-compatible features and documented deployment additions.
- `PATCH`: fixes, docs, tests, or low-risk refinements that should not require a
  new deployment shape.

Before `1.0.0`, the project is still pre-stable. A minor release may still
carry notable operator-facing changes, but the release notes will call them out.
