# Shared photo playback

The implementation is opt-in and **not deployed**. Server compilation, frontend
and Go tests, two-browser acceptance, and four source-negative proofs passed on
the NAS on September 13, 2026. Android build/signing and physical acceptance are
separate remaining gates. Neither physical Lenovo frame was used for this work.
Installed TV and server settings are unchanged. See [validation evidence](PHOTO-SYNC-VALIDATION.md).

## Joining a group

Configure the Kiosk server with an existing curation profile and an interval:

```yaml
kiosk:
  sync_groups:
    living-room:
      curation_profile: balanced
      duration: 45
```

Restart Kiosk after adding or changing groups. Use the same group in each
participating Photos URL, for example:

```text
http://KIOSK_HOST:3000/?sync_group=living-room&frame_id=tv-living-room
http://KIOSK_HOST:3000/?sync_group=living-room&frame_id=lenovo-living-room
```

Keep `frame_id` unique; FrameOS can supply its configured Companion identity.
The server permits at most eight groups with 1–48 lowercase letters, numbers,
hyphens or underscores (first character letter/number), and 10–3600 seconds per
photo. Unknown groups fail closed. No group is allocated from arbitrary URLs.

The group profile chooses the same photo for everyone. It does not merge each
screen's individual preference history or cross-frame repeat suppression;
those continue to apply to independent slideshows. Existing ungrouped URLs keep
the independent slideshow. Remove `sync_group` to leave shared mode.

## Playback and controls

The server selects and preloads current/next photo IDs with shared deadlines.
Each screen renders the selected image at its own bounded dimensions and
corrects for its local clock. A late join displays the current photo; reconnect
catches up without replaying a backlog. Small network/decoder timing differences
are expected; this is coordinated photo playback, not frame-accurate video.

Next, Previous, Pause and Resume apply to the group. Browser controls use the
existing buttons, Left/Right, P to pause and Shift+P to resume; Space toggles.
A command includes its session and version, so duplicate or stale commands cannot
advance twice. Native FrameOS pause state follows shared state idempotently.

Leaving Photos suspends the web player. A disconnected shared participant keeps
its last complete image and retries; FrameOS does not rotate its local offline
reserve in shared mode. This is intentional so disconnected screens never start
a different sequence. Shared mode currently displays still JPEG photos; video,
live-photo playback, the independent progress bar and More Info overlay are not
part of this first implementation. Image captions and per-display layouts remain.

## Architecture and validation

Seven `custom-image/shared-playback-*.patch` files are applied in Dockerfile order
on the pinned upstream source. The coordinator stores bounded IDs/routing data,
not decoded photos or credentials. Per-screen rendering uses the existing work
gate, request cancellation, classification revalidation and dimension limits.
The wire API is under `/api/playback/:group`; commands require same-origin JSON
and compare-and-swap session/version. No credentials enter playback payloads.

`ci/shared-playback-nas.sh` in the materialized source runs the frontend tests,
Go tests/race checks, compilation, two-browser synthetic-photo acceptance and
source-only negative proofs. It exports the binary and validation evidence.
`scripts/frame-sync-android-nas.sh` runs FrameOS and TV Android checks and emits
unsigned APKs, mapping, identity, test reports and proof logs. Local signing and
physical frame/TV acceptance are separate steps. Actual hardware playback is
**UNVERIFIED** until explicitly approved and exercised.
