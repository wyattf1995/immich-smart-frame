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
4. Run `./scripts/validate.sh` on the target revision before replacing a
   working display.

## Upgrade flow

```sh
git fetch --tags
git checkout TAG_OR_BRANCH
docker compose build immich-kiosk
docker compose up -d immich-kiosk
docker compose ps
```

Then verify:

- the slideshow loads from another device on the LAN;
- the frame renders a full-resolution image;
- the active curation profile still works;
- logs show no patch-application or startup failures.

If the deployment also uses FrameOS, stage the new APK and router separately.
Install the APK with `adb install -r` so configuration and encrypted sessions
remain intact. Exercise Photos, Home, Weather, Cameras, and Calendar through a
staged router path before renaming it into place. Keep the prior router and
private config under distinct known-good names on the frame.

## Rollback flow

If the new revision fails, roll back to the last known-good repository tag:

```sh
git fetch --tags
git checkout LAST_KNOWN_GOOD_TAG
docker compose build immich-kiosk
docker compose up -d immich-kiosk
docker compose ps
```

Rollback is safe because secrets and active config live outside Git in `.env`,
`config/config.yaml`, and `secrets/immich_api_key`.

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
