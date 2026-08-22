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
- { type: date, value: "last-30", weight: 35 }
- { type: date, value: "last-180", weight: 20 }
- { type: date, value: "last-730", weight: 10 }
```

A photo from the last month can be returned by all three buckets. A
six-month-old photo can only be returned by the widest bucket. The overlap
therefore creates a natural decay curve while the remaining profile weights
continue to surface older family, travel, or photography assets.

In the advanced example, these buckets total 65. With profile weights totaling
100, at least 65% of selections come from the last two years, and the overlap
boosts the newest photos again inside that share.

This is bucket weighting, not a persistent per-photo score. Kiosk's cache
reduces short-term repeats, but the project does not yet maintain a lifetime
“already shown on this display” ledger.

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

## Positive sources versus fresh untagged photos

A tag/person-only profile excludes an untagged archive straggler by
construction. A date source intentionally admits any asset in its time window
that passes global exclusions. That is useful when ingestion is faster than
tagging, but it is a tradeoff: an untagged recent document can appear.

Practical options:

- use date sources for freshness and keep the windows narrow;
- maintain hard exclusion tags for known junk classes;
- omit date sources when strict positive-source eligibility matters more;
- add a separate recent profile and select it only on chosen displays.

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
