# Frame remote

A small phone remote and Home Assistant control plane for FrameOS. It stores
settings, expiring actions, bounded status history, and reversible photo choices
in SQLite. It does not execute commands on the host, proxy arbitrary URLs, or
change Immich originals. Python's standard library is the only runtime dependency.

## Deploy

Copy this directory to persistent appdata. Create a private `config.json` from
the example, replacing **every** credential with a unique random value (at least
32 random bytes). Use one device token per frame and a separate operator token
for Home Assistant. Set the exact HTTPS public origin. Configuration is read at
startup; credential rotation requires a service restart and device reprovisioning.

Create `data/` owned by UID/GID 10001; make `config.json` mode 640 and readable by
that group. Build with `docker compose build`, run the unit tests first, then
`docker compose up -d`. The published port is localhost only. Terminate HTTPS at
the trusted reverse proxy. An outbound SSH tunnel should allow only its single
fixed remote listener, deny shell/local forwarding, and restrict listener access
to the proxy network. Do not expose port 8092 directly to the internet.

The browser uses Basic authentication over HTTPS. Mutation requests also require
the configured Origin and a session CSRF value. Device Bearer tokens can only
poll their own device route. Operator tokens can control configured frames but
cannot act as a device. Credentials are accepted only in the Authorization header.
The service does not log requests, secrets, or household status.

Mount `data/export/` as a directory read-only into Kiosk and set
`FRAME_PREFERENCES_FILE=/frame-preferences/preferences.json`. Mounting the directory
preserves atomic replacement semantics. Add `frame_id=main` to that frame's Kiosk
URL so the selection policy and the phone remote address the same frame. The
export contains asset UUIDs and preference values, so treat it as private data.

## Protocol

Devices POST `/device/poll` with `{schema:1,status:{...},acks:[...]}`. The response
contains schema, deviceId, serverTime, pollAfterMs, settingsRevision, settings,
at most one command, and a full `hiddenAssets` UUID snapshot for that device.
Persist this snapshot privately and remove hidden images from the offline reserve;
a missing snapshot is not permission to clear previous exclusions. Poll while the app is resumed, at five-second intervals,
with bounded timeouts and backoff. Commands expire after 60 seconds and are only
accepted while the frame's heartbeat is less than 90 seconds old. Persist command
IDs and acknowledgements; an acknowledgement is terminal and device-scoped.

Allowed commands: `show_mode` with `mode`, `photo_next`, `photo_previous`,
`photo_pause`, `photo_resume`, `photo_hold` with `durationSeconds` (15–3600), and
`set_profile` with a configured profile. Use terminal `dispatched` when a key/tap was sent but its page result cannot be
attributed to that input. Use `applied` for a completed native state change, or
`failed`, `rejected`, `expired`. A later acknowledgement does not replace a
terminal result; photo freshness is reported independently.

Operator API: GET `/api/state`, GET `/api/preferences?deviceId=main`, and POST
`/api/command` (`deviceId`, `command`), `/api/settings` (`deviceId`, `patch`),
`/api/feedback` (`deviceId`, `feedback:{assetId,preference}`). Preference values are
`more`, `less`, `hide`, `clear`. Basic-authenticated mutations require the Origin
and `X-Frame-CSRF` returned by `/api/state`. Operator Bearer tokens support server
automation; browser requests from a different Origin are rejected for all roles.

Status timestamps are Unix milliseconds. `lastSeenAt` is server-observed app
liveness; `lastPhotoAt`, `lastWeatherAt`, and `lastPaintAt` are distinct device
signals. Missing values mean unknown, not healthy. The remote also shows the app
version, recovery count, offline asset count, command acknowledgements, and settings
revision. A configured parents preset does not mean a physical frame is provisioned.

`lastPaintAt` reports when FrameOS recognizes a displayed, paint-qualified native
surface or web document. Returning to a qualified warm document can refresh it
without a new full-page load. Home/Calendar fragment switches reuse that document;
the wrapper separately checks its embedded route's readiness. This timestamp is
not a measurement of target-route pixel latency. `lastPhotoAt` remains the
independent accepted-photo signal.

## Optional event notes

When a frame has `eventOverlays: true`, an operator may POST `/api/event` with
`{"deviceId":"main","event":{"type":"calendar","text":"An event starts soon","expiresInSeconds":120}}`.
The only types are `calendar` and `reviewed_bird`; do not label an unreviewed
model result as a reviewed bird. Text is limited to 100 characters, expiry to
30–300 seconds, and delivery to one event per frame every 15 minutes. Events
may be redelivered in polls until expiry. Devices persist event IDs before display
and suppress duplicate IDs across restarts. Expired events are never delivered.
The device shows notes only in active Photos, outside quiet hours and a manual pause.

Home Assistant configuration and controls are in [home-assistant/](home-assistant/).

## Verification and rollback

Run `python3 -m unittest discover -s companion/tests -v` from the repository root.
Tests cover expiration across restart, bounded queues, device-scoped acknowledgements,
credential separation, CSRF/Origin enforcement, atomic settings, and preference undo.
Verify signed-out access is 401, a device token cannot read operator state, and an
operator token cannot poll as a device. `/healthz` discloses only process health.

Back up `data/` with the container stopped for a consistent copy of SQLite and its
WAL. Back up private configuration separately. Rollback by restoring the previous
image and matching state backup; remove remote provisioning on the device if needed.
The frame's local navigation remains usable when the companion is unreachable.
