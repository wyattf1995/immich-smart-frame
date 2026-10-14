# FrameOS Touch source validation — October 13, 2026

> **Newer checkpoint:** [October 14 source validation](frameos-touch-validation-20261014.md)
> records a later source revision and its own artifacts and checks. This historical
> record remains unchanged for the source it describes.

Verified October 13, 2026, from retained build outputs and their original-job
export, archive, and closed-lifecycle receipts.

## Source and artifact boundary

The compiled source is `62d2c67b680f012c797dfef086da8a204d5126b5`.
The publication merge adds documentation only; all production source, tests,
and build inputs are identical to that validated source. The isolated build
transport contains 332 reviewed files with hashes checked against Git.

The build produces an unsigned `com.wyattfleming.touchframe` APK for the
API23/ARMv7 compatibility path, targeting API37. The artifact manifest, native
ABI, and absence of a signing block were independently checked. This source publication does not install,
sign, or release an APK or change a device launcher.

## Verified results

- Compilation, release lint (no Error/Fatal issues), and unsigned release assembly passed.
- 263 shared/Touch JVM tests across 70 XML reports: zero failures, errors, or skips.
- 53 source-only negative checks across 23 focused Touch suites, plus one
  supplemental Weather-header check, all followed by restored-green runs.
  Source and test hashes were checked after restoration; tests were unchanged.
- All five declared artifacts matched their exported and archived SHA-256 values.
  The canonical worker lifecycle is closed, with no active sibling record.

The successful build's initial export client failed with a filesystem mount
error. Recovery exported and archived that same completed job without rebuilding.
A reviewed recovery verifier preserved the original source, test, proof, lint,
and APK checks and bound them to the original successful job. Independent checks
also bound the recovered copy to the canonical closeout and worker lifecycle.
The failed client record and earlier proof-harness failures remain retained.

| Artifact | SHA-256 |
| --- | --- |
| Unsigned Touch APK | `38983b9eef7229d3e99a155497d20964482fcb3998f013f281a5e3368f0ef78f` |
| Release mapping | `eaedc46e6edcf06d721e0f8f0039495f719d22bde8e368cadb33797722a62dad` |
| Validation reports ZIP | `17cbd20333a7d93502d8a3770d5ac8b660024236be08f6d6358e265aef86737b` |

The mutation-target preflight has three focused Python tests. Publication
verification independently disabled each of its two source guards: the existing
replacement guard and the unique-original-selector guard each caused its own
unchanged test to fail. Restoring only source returned all three tests to green.
No assertions were removed or relaxed.

## Scope and acceptance limits

The aggregate repository `validate.sh` was not rerun for this publication; its
applicable Touch compilation, lint, unit, and proof gates were run through the
isolated Android validation path above. Documentation claim tests passed (3/3),
and changed documentation links and whitespace were checked.

Touch/shared Android evidence is not a fresh full build of the separate older
FrameOS or TV wrappers. Their earlier release evidence and targeted shared
Weather checks remain separate; a future legacy/TV release must validate its
own final source and artifacts. The unchanged Companion source has separately
retained 44-test/11-negative-proof validation.

Earlier small-frame Calendar/Weather observations refer to `e7403b4f`; earlier
large-frame media observations refer to their own signed candidates. Neither
accepts this later combined build by inheritance. Physical gestures, normal
boot, endurance, and per-device acceptance remain explicit release gates.

See the [design handoff](frameos-touch-design-handoff.md),
[compatibility guide](compatibility.md), and [release gates](releasing.md).
Household screenshots, device identifiers, signing material, and operational
receipts are retained privately, outside the public repository.
