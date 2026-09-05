# Curation profiles

The custom Kiosk image selects a source bucket first, using the exact weights
in the active profile, and then asks Immich for a random valid asset from that
source. Global exclusions are applied after retrieval.

## Source schema

Each source has a `type`, optional `value`, and positive `weight`:

```yaml
curation_profile: recent
curation:
  profiles:
    recent:
      sources:
        - { type: date, value: "last-30", weight: 40 }
        - { type: memories, weight: 10 }
```

Supported types:

| Type | Value | Notes |
| --- | --- | --- |
| `album` | Immich album ID or supported album keyword | Uses the album source path |
| `person` | Immich person ID or `all` | `all` selects among named people |
| `tag` | Exact Immich tag path | The tag must already exist |
| `date` | `last-X`, `today`, or `YYYY-MM-DD_to_YYYY-MM-DD` | Dynamic ranges use the Docker host timezone |
| `memories` | omitted | Skipped when Immich has no current memories |
| `random` | omitted | Opens the full otherwise-valid library |

Profile names are matched case-insensitively. A profile can be selected in
YAML or with `?curation_profile=NAME` when URL queries are enabled.

## Why overlapping date ranges work

Consider three sources:

```yaml
- { type: date, value: "last-30", weight: 8 }
- { type: date, value: "last-90", weight: 12 }
- { type: date, value: "last-180", weight: 10 }
- { type: date, value: "last-730", weight: 10 }
- { type: date, value: "1900-01-01_to_today", weight: 10 }
```

A photo from the last month can be returned by every bucket. A six-month-old
photo can only be returned by the wider buckets. The overlap therefore creates
a natural decay curve, and the final all-time rung keeps every library photo
reachable so the archive never goes completely dark.

In the advanced example, these buckets total 50. With profile weights totaling
100, half of all selections come from the recency ladder, and the overlap
boosts the newest photos again inside that share. Keep the top rung's weight
modest: a sliding `last-30` window can collapse to a handful of photos when a
large shoot ages out, and any weight it carries then concentrates on whatever
remains.

This is bucket weighting, not a persistent per-photo score, but three pool
behaviors bound repetition:

- Date pools refill without discarding unserved candidates and track served
  IDs, so a pool smaller than `fetched_assets_size` cycles through every photo
  once before any repeat (a shuffle bag).
- `kiosk.date_pool_minimum` (default `0`, disabled) widens a `last-N` window
  (N times 3 per step, capped at ten years) whenever a refill returns fewer
  candidates than the configured floor, logging a warning per step. Widening
  re-evaluates from the original window on every refill, so it stops on its
  own once the library recovers.
- `kiosk.date_pool_session_spread` (default `true`) picks a random capture
  hour before picking a photo inside it, so a thousand-frame burst afternoon
  carries no more weight per pick than a single photo from another day.

Frame selection also keeps its own bounded history: the last 24 accepted
assets per frame. `FRAME_CAPTURE_BURST_SECONDS` defaults to `300`, rejecting a
different photo captured within that window of a recent selection; set it to
`0` to disable that capture-time check. Assets with no `localDateTime` remain
eligible. `FRAME_CROSS_FRAME_REPEAT_SUPPRESSION=true` additionally rejects the
last 24 selected asset IDs across frames. Both repeat checks relax only on the
final bounded selection attempt; a `hide` preference is never relaxed.

The authenticated aggregate metrics endpoint reports
`date_pool_widenings_total` and `date_pool_last_effective_days`. These describe
process-wide date-pool widening only; they contain no frame, request, asset,
or user identifiers. The first is a cumulative count, and the second is the
window used by the most recent eligible `last-N` refill.

`cache_duration` (seconds) extends both the backend cache and the date pool
TTL; an hour keeps recently served photos out of the immediate remix.

## Boosting a milestone album

Add an album as its own positive source when a personally important collection
should appear more often without becoming the only slideshow:

```yaml
- { type: album, value: "replace-with-your-private-album-id", weight: 12 }
```

Subtract the same weight from other sources so the profile still totals 100.
An asset that is also eligible through a person, tag, or date source can appear
more often than the album's nominal weight. Keep real album IDs only in the
untracked `config/config.yaml`, never in the public example or git history.

## De-ranking an album without excluding it

An album penalty keeps matching photos eligible while reducing how often they
win selection through any source in that profile:

```yaml
curation:
  profiles:
    balanced:
      sources:
        # ...weights totaling 100...
      album_penalties:
        - { album: "replace-with-your-private-album-id", factor: 0.20 }
```

The factor is an acceptance probability greater than 0 and at most 1. A factor
of `0.20` accepts roughly one in five matching candidates; use
`excluded_albums` instead when the desired probability is zero. If an asset is
in multiple penalized albums, the strongest (lowest) matching factor wins.
Penalties are profile-specific, do not consume source weight, and apply whether
the candidate arrived through a date, person, tag, memory, or album source.

Date sources retain a decoded, concurrency-safe candidate pool until the normal
cache expiry; each candidate is removed before evaluation. Keep
`fetched_assets_size` between 100 and 200 on the frame host (the examples use
150) to bound memory while retaining useful variety.
The accept/reject decision is stable for an asset within one slide request, so
selection retries cannot repeatedly re-roll a rejected candidate.

## Hold imported photos until classification

Set `kiosk.require_ai_caption: true` (or `KIOSK_REQUIRE_AI_CAPTION=true`) when
using the [tagging pipeline](../tagging/RUNBOOK.md). The default is `false` for
libraries without this pipeline. This setting cannot be overridden by a URL.

With it enabled, every fresh source requires a complete, nonempty
`[AI] … [/AI]` description and rejects conservative timestamped screenshot
filenames. Keep `Skip/**` in `excluded_tags` to reject classified screenshots,
documents, memes, graphics, and other non-photo material. Metadata failures
reject the candidate. A caption records prior processing; it is not a guarantee
that an older classifier correctly identified non-photo content.

Previous/Next history fetches fresh metadata and rechecks the exact target.
A target that has since become excluded returns an error; history does not
silently substitute another photo. Enabling the gate changes the NAS offline
pool scope, preventing earlier pool entries from being served. Device reserves
are separate: after a bulk exclusion change, cycle the authenticated frame
profile to another profile and back, verifying fresh photo acknowledgements
and reserve refill. This preserves the final profile while removing stale
on-device photos. No ROM reboot is needed.

Classification backlogs stay off the frame until processing completes. Photos
with human descriptions that the tagger correctly preserves may remain held
if they lack the required AI marker. Broad date sources still provide archive
coverage among eligible photos. With the gate disabled, those same date sources
can admit unclassified imports before exclusion tags exist.

## Qwen example

[qwen.example.yaml](../config/qwen.example.yaml) demonstrates one controlled
taxonomy with paths such as `People/Family`, `Animals/Dog`,
`Activity/Travel`, and `Style/Landscape`. It is an example, not a requirement.

Large-enum constrained VLM output was unreliable in the original deployment.
The successful approach produced free-form factual captions/tags, then mapped
terms onto a controlled taxonomy with ordinary code. Do not assume a vision
model can judge flattering expressions, sharpness, composition, or personal
importance merely because it detects a face or scene.

## Editing safely

Keep each profile easy to audit:

1. Use weights totaling 100.
2. Comment intentional overlaps.
3. Prefer positive source types over a full-library `random` pool.
4. Test a profile with `?curation_profile=NAME&cache=false` before making it the
   default.
5. Preserve a small memories weight if “on this day” variety is desired.
6. Use an album penalty for soft de-ranking and `excluded_albums` for a hard
   ban.
