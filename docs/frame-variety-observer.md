# Frame variety observer

`observe-frame-variety.py` is a one-off, read-only observer for the existing
Frame companion SQLite database. It runs independently of Kiosk and its soak
collector, opens the database with SQLite `mode=ro`, and never writes the
companion database, Kiosk, or any service configuration.

Run it on the host that has the companion `state.db` file mounted:

```sh
python3 scripts/observe-frame-variety.py /path/to/state.db > frame-variety-24h.json
```

The default is a bounded 24-hour run with a two-second sample interval. Both
are configurable, but the duration is capped at 24 hours:

```sh
python3 scripts/observe-frame-variety.py /path/to/state.db \
  --duration-seconds 21600 --sample-seconds 2 > frame-variety-6h.json
```

The output is one JSON object whose values are numeric aggregates only. It
contains no device labels, profiles, asset IDs, identifier hashes, raw status,
or per-event timestamps. Sequence boundaries are retained internally per
(device, profile). The uniqueness count is therefore the sum of distinct assets
within each device/profile sequence, not a global distinct-asset count; an asset
visible on two frames or profiles is not treated as a repeat across those
separate presentation sequences.

`observations` counts only a changed asset ID while the latest companion status
is online, unpaused, in Photos, and fresh. Online uses the companion
`lastSeenAt` rule (a poll no more than 90 seconds old); future photo or device
timestamps are rejected. `uniqueRatioPpm` is the fraction of
those observed changes that were unique within their sequence, scaled by one
million. Repeat distances are the number of observed changed-asset events since
the prior occurrence in the same sequence. `ignoredSameAsset` records receipt
refreshes of the current ID; it is deliberately not treated as another view.
`readFailures` covers busy or unavailable read-only database samples.

This is a sampled presentation measure, not a Kiosk selection trace. FrameOS
normally polls the companion every five seconds, so quick manual moves that
change and return between samples can be missed. It cannot measure adjacent
identical re-renders: the status exposes only the latest asset ID. It also
cannot measure capture-burst concentration or prove the capture-burst
suppression policy: `lastPhotoAt` is the Android receipt time, not an Immich
asset capture time, and neither selection attempts nor their rejection reasons
are exported.
