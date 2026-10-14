# FrameOS Touch source validation — October 14, 2026

Verified October 14, 2026 from retained build artifacts, checksums, and the
closed build receipt. This is a source-validation checkpoint for
`fb9a86d83cd4cfd8f1bb99bc961e40bc6a381f96`; it is not a signed release, an
installation record, or device acceptance.

## Source and artifact boundary

The validated package is `com.wyattfleming.touchframe` for the legacy ARMv7
path (`armeabi-v7a`, min SDK 23, target SDK 37). The retained APK is unsigned
and was neither installed nor deployed by this validation.

| Artifact | SHA-256 |
| --- | --- |
| Unsigned Touch APK | `d477f94782f0c57a271f39e5bafbbef30d93b810bbb76c6515034846b3eada96` |
| Release mapping | `9fcfe11f07fe9ec045b3276e814ee0ed5df1514260dbe2befe0e1de5caae92de` |
| Validation reports ZIP | `d6d4f767bb67798c27561f83853ee805b8355c8cdb2edd94130672b8b5240ff9` |

The retained artifact manifest and independent verification receipt agree on
those APK and mapping hashes. The source identity report also records both
artifact sizes and the unsigned signing state.

## Verified results

- Release lint and unsigned release assembly completed successfully.
- 314 shared/Touch JVM tests across 82 XML reports completed with zero failures,
  errors, or skips.
- The established Touch source-mutation matrix ran 53 negative checks, and the
  design proof ran 14 mutations. Each negative run was followed by its restored
  green check with unchanged guarded tests.
- Supplemental proofs covered the Android HOME startup policy, legacy Home
  Assistant climate-unit fallback and null/range target rejection, the bounded
  weather label, and live-camera capability and mode boundaries. Their retained
  red/green results completed without errors or skipped cases.
- Retained APK, mapping, report, source-identity, validation-log, and design-proof
  artifacts matched the manifest hashes; the successful build lifecycle was
  closed after archive verification.

## Scope and acceptance limits

This evidence verifies the exact unsigned source build and its automated checks.
It does not verify a signed artifact, provisioning, authentication, launcher or
boot behavior, live-camera playback, touch interaction, network recovery, or
any particular frame family. Those require a separately recorded test of the
exact signed artifact on the intended hardware.

The earlier [October 13 checkpoint](frameos-touch-validation-20261013.md) remains
an historical record for its earlier source revision. Neither checkpoint grants
acceptance to a later revision by inheritance. Keep private device captures,
credentials, signing material, and operational receipts outside the repository.

See the [Touch guide](frameos-touch-app.md), [design handoff](frameos-touch-design-handoff.md),
[compatibility guide](compatibility.md), and [release gates](releasing.md) for the
remaining acceptance work.
