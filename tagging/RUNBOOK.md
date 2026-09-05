# Screenshot classification and additive backfill

`tagger_policy.py` is a drop-in successor to the committed legacy snapshot. Keep
`policy.py` beside it when mounting or copying it into the existing controlled
runtime. Every write requires `--apply`; `--dry-run --ids <file>` is read-only
and does not call tag creation or any tag/caption write. It preserves the legacy controlled vocabulary, `[AI] … [/AI]` caption
replacement rule, and its no-GPS/no-original-write behavior.

The policy fast path accepts only strong `originalFileName` forms: a standalone
leading `Screenshot`, `Screen Shot`, or `ScreenCapture`, optionally after a
calendar/timestamp prefix. It never applies that rule to an asset ID or an
original path. All other images require VLM JSON with `tags`, `caption`, and
one `image_type`: `photograph`, `screenshot`, `document`, `meme`, `graphic`, or
`other`. A missing or truncated image type fails before the checkpoint write,
so it is retried rather than marked complete. Non-photo types map to the
controlled `Skip/*` taxonomy. Skip tags are placed before the 12-content-tag
limit.

## Safe deployment sequence

1. Wait for the current auto-tagger host lock to release. Do not run a second
   tagger or VLM/GPU workload in parallel.
2. Under the existing migration and sidecar lifecycle gates, preserve the live
   launcher/source hashes and install `policy.py` plus `tagger_policy.py` as a
   bundle. Verify syntax and the exact new hashes before changing the launcher
   invocation.
3. Run the existing controlled dry-run workflow first. It must show no
   credentials, asset IDs, filenames, captions, paths, or raw VLM output in its
   evidence.
4. Confirm the sidecar queue is paused before and after any backfill. The
   backfill itself does not change sidecar state.
5. Roll back by restoring the retained legacy source only if the guarded
   validation fails; never interrupt an active run to switch sources.

## Fast existing-screenshot backfill

`filename_backfill.py` accepts only explicit candidate JSONL from stdin. Each
row must contain `id` and `originalFileName`; rows are kept in memory and are
not logged or written to a checkpoint. It fetches each matching asset's current
metadata so an already-present `Skip/Screenshot` relationship is left alone.

Default mode is read-only planning. With `--apply`, it creates or resolves only
`Skip/Screenshot` and sends additive relationship writes in batches of at most
500 IDs. It never changes descriptions, captions, EXIF, GPS, originals, or any
other tag, and never invokes VLM/GPU. The caller should generate the bounded
candidate JSONL from a read-only query, keep it in memory or a protected pipe,
and compare only aggregate counts in evidence.

```text
<protected candidate JSONL producer> | python3 filename_backfill.py --ids-stdin
<protected candidate JSONL producer> | python3 filename_backfill.py --ids-stdin --apply
```

The tool fails closed on malformed candidate rows, response limits, metadata
mismatch, transport failures, and missing tag identifiers. Re-running a
partially completed apply is idempotent because the relationship is checked
before each batch is selected.
