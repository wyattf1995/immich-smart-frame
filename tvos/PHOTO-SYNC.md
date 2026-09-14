# Shared photo playback proposal

Requested September 13, 2026: when the living-room TV and Lenovo frame are both
in Photos, show the same photograph and advance together. This is a proposed
feature, not an installed capability. No frame or slideshow server was changed
during this investigation.

## Existing behavior

Frame TV embeds the configured Immich Kiosk page in `DashboardActivity`. Each
page currently requests its own photos and advances on its own timer. Using the
same album or curation profile does not synchronize playback.

`frame_id` identifies a physical frame. The custom Kiosk uses it for preferences
and recent-photo history (`custom-image/photo-variety.patch`); FrameOS supplies
its Companion device ID (`FrameControlReceiver.kt`). Sharing that value would
mix preferences and selection history. Keep a unique device ID for each display.

Cross-frame repeat suppression in `custom-image/burst-variety.patch` currently
tries to avoid showing the same recent asset across independent displays. Shared
playback must explicitly select once for the group and distribute that result,
rather than asking that selector separately for each group member.

## Proposed behavior

- Add an optional named group, initially “Living room,” separately from device ID.
- A server-owned session selects one photo through the existing curation path and
  publishes a revision, photo identity, start time, next transition time and pause
  state. Immich credentials remain on the server.
- Both viewers preload the same photo and display it at the scheduled time.
  Modest network/decoder timing differences are expected; frame-perfect playback
  is not the target.
- A display entering Photos joins the group's current revision. Reconnecting
  adopts the current photo rather than replaying every missed transition.
- Leaving Photos releases that display's subscription and renderer; streaming,
  casting, and other modes must continue to work independently.
- Next and pause operate on the group. Duplicate requests/reconnects cannot
  advance the session twice. Individual preferences stay device-scoped until
  group-preference behavior is explicitly designed.
- If the group server is unavailable, retain the last complete photo and retry
  with bounded backoff. Do not secretly switch to an independent sequence.
- Devices outside a group retain their current independent slideshow behavior.

The natural implementation point is the shared Kiosk backend and web viewer so
both Android clients can reuse it. Confirm compatibility with FrameOS photo
controls/recovery before adopting a TV-only wrapper or changing deployed URLs.
Do not modify either physical Lenovo frame merely to test the TV.

## Acceptance checks before deployment

Use two independent browser clients first: matching asset and revision across
multiple transitions; late join; one viewer offline; reconnection; simultaneous
Next; pause/resume; independent device histories; unchanged ungrouped selection.
Verify no credentials enter client URLs or session payloads, lifecycle cleanup,
and existing frame control behavior. Physical frame plus TV acceptance is a
separate, explicitly authorized step.
