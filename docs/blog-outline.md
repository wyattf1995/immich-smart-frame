# Blog outline: rescuing a discontinued Lenovo Smart Frame

> Maintainer writing notes, not installation documentation. All measurements
> are from one private deployment and should be re-verified before publication.

## Thesis

The useful part of a digital frame is not the panel; it is the policy that
decides what deserves to appear. The discontinued Lenovo app failed both as a
reliable client and as a curation system. Reusing the hardware became the final
consumer-facing payoff for building a local photo archive around Immich.

## Narrative arc

1. The frame had spent years in a closet after Lenovo stopped supporting its
   companion app.
2. It reappeared on Google WiFi as “Motorola Android.” The Motorola OUI was a
   clue, not a different device: the frame is a MediaTek Android appliance.
3. With no touchscreen, USB-C OTG, Key Mapper, the hardware star button, and one
   USB-debugging authorization established ADB control. Wireless ADB was then
   enabled for provisioning.
4. Fully Kiosk Browser made the web UI fullscreen, while Immich Kiosk on the NAS
   handled image retrieval, resizing, transitions, caching, and prefetch.
5. Two “slow frame” symptoms had unrelated causes:
   - a failed Bluetooth pairing generated more than 1,046,000 bond events and
     418,000 Bluetooth wakelock acquisitions;
   - MediaTek DuraSpeed logged `bringUpServiceLocked, suppress to start service!`
     while blocking Fully's foreground/WebView child services.
6. The image-quality bug was a coordinate-system mismatch: 1920x1080 physical
   pixels, 960x540 CSS pixels, DPR 2. Kiosk trusted the CSS dimensions and served
   a half-resolution image. A four-line frontend patch made the requested target
   DPR-aware without buying Fully PLUS or granting its JavaScript bridge broad
   device permissions.
7. `album=all` proved that “random” is not “curated.” An eight-year-old jeans
   listing appeared. Excluding `Things/ProductListing` helped, but a Dodge
   service-manual photo then appeared because it was one of the approximately
   0.5% of archive photos with no Qwen caption or tags.
8. That failure motivated the durable baseline: select from positively weighted
   Qwen/face sources, then apply hard exclusions. Unknown older assets are
   excluded by construction.
9. A live sample then exposed a different archive-size bias: 15 of 16 balanced
   selections were from 2022 or earlier even though thousands of recent photos
   were available. An overlapping 30/180/730-day ladder now reserves 65% of the
   balanced profile for recency while retaining the curated evergreen mix.
   Those recent windows intentionally allow not-yet-tagged photos that still
   pass the hard exclusions; this is a narrow freshness exception rather than a
   return to archive-wide random selection.
10. A later personalization added one important album as a direct, local-only
    source and softly de-ranked an overrepresented album without excluding it.
    The public repository documents both patterns but never contains the
    private Immich album IDs.

## Verified technical facts (2026-08-20)

- Device: Lenovo CD-3L501F / `Walnut`, Android 10, MediaTek MT8167s, 2 GB RAM,
  16 GB storage, 21.5-inch 1920x1080 panel.
- Renderer: Fully Kiosk Browser 1.61.2.
- Server: Immich 3.0.3 and pinned Immich Kiosk 0.42.0.
- Custom image: `0.42.0-lenovo-curation1`.
- Qwen model used by the earlier archive pass: `qwen3-vl:8b-instruct`.
- Qwen coverage: approximately 90,822 of 91,236 timeline photos (~99.5%).
- Recency inventory at rollout: 2,872 images from the last 30 days, 8,110 from
  the last 180 days, and 19,110 from the last 730 days. These are overlapping
  counts from the active Immich timeline.
- Live browser request after the DPR fix: `client_width=1920`,
  `client_height=1080`.
- Lower-left metadata was verified on the physical frame at 1920x1080 with an
  exact capture date and city/state; photos lacking location data render no
  empty location icon.
- Typical NAS original fetch/resize was well under one second after warm-up;
  prefetch hid it from the 45-second slide interval.
- Docker build runs config, weighting, and route tests before compiling the
  final binary.

## Architectural decisions worth explaining

- Keep the frame dumb. The NAS is easier to update, inspect, and back up.
- Pin upstream and carry small patches instead of forking an entire application.
- Keep credentials in Docker secrets.
- Do not mutate Immich tags or originals for display selection. The Kiosk key is
  read-only.
- Make weights human-readable YAML and profile-selectable by URL.
- Treat Qwen labels as useful retrieval signals, not aesthetic truth.
- Keep memories as a bounded minority source.
- Keep optional metadata useful but quiet: exact date on every photo, location
  only when present, country omitted, and simple CSS spacing rather than a
  GPU-heavy overlay effect.

## Honest limitations

- `person: all` currently chooses among named people using Kiosk's existing
  behavior; it does not encode relationship priority.
- Face recognition says who is present, not whether the expression is flattering.
- Qwen tags do not measure sharpness, composition, or near-duplicate bursts.
- The next meaningful feature is a low-friction reject/boost feedback loop.
- Wireless ADB on the stock firmware does not survive reboot.

## Publication checklist

- Replace LAN addresses with placeholders.
- Choose personal-photo screenshots explicitly; do not publish them by default.
- Add a simple architecture diagram and before/after DPR crop.
- Link the exact upstream Kiosk release and both local patches.
- Re-run the measurements immediately before publication and date them.
- Confirm the repository's AGPL-3.0 attribution remains aligned with the pinned
  Immich Kiosk release.
