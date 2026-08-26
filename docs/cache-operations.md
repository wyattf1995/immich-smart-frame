# Cache and offline-pool operations

The kiosk image has two separate cache surfaces:

- Hashed CSS and JavaScript are immutable HTTP assets. The HTML document and
  `/image` and `/video` responses are private and must revalidate; they must
  not be treated as a shared CDN cache.
- `offline-assets/` is a durable, host-mounted photo-data pool. It is scoped by
  kiosk generation, Immich origin, selected user, device ID, curation profile,
  and a hash of the sanitized configuration. The manifest stores only the
  resulting scope hash, not those identifiers or API credentials.

The old fixed `immich-kiosk` service-worker cache is removed by the browser
startup cleanup and by the worker activation path. The worker no longer caches
the root document. A release therefore cannot remain pinned to an old HTML
document after an upgrade. The browser contract is checked by:

```sh
./scripts/test-browser-cache-contract.sh
```

Successful tag, favorite, hide, and rating mutations, as well as an explicit
cache flush, invalidate the durable offline pool. The next request recreates
the manifest and prewarms a pool for its current scope. This prevents a
previously hidden asset from remaining in the kiosk pool after a kiosk-side
mutation. Changes made directly in Immich or by another kiosk device require a
normal pool expiration or an explicit `/cache/flush` request.

## Offline volume provisioning and upgrade migration

The bind mount is intentionally writable by the image's non-root runtime user
(UID/GID `65532`) and private to that user:

```sh
install -d -m 700 -o 65532 -g 65532 offline-assets
KIOSK_OFFLINE_UID=65532 KIOSK_OFFLINE_GID=65532 \
  ./scripts/check-offline-assets-permissions.sh
```

Run the preflight immediately before `docker compose up`. It rejects missing
or symlinked paths, a wrong owner, and group/other permissions. The compose
mount remains read-write because prewarming must replace stale pools; the
container itself remains non-root and read-only outside its temporary files.

Before an upgrade, snapshot `offline-assets/` with
`scripts/deployment-input-snapshot.sh`. A release upgrade invalidates the old
pool on its first request because `generation` changes; the old files are
deleted from the live pool and rebuilt. Keep the protected snapshot until the
new pool has been observed, then expire it according to the household backup
policy. A rollback similarly rebuilds the pool for the rolled-back generation;
restoring a snapshot is optional and must pass the snapshot verifier first.

The current source uses `KioskVersion` as the generation fallback. Integration
with the generic cache-generation API should replace that value with the
API's monotonic generation at image-build integration time, while retaining
the existing manifest fields and invalidation hooks.
