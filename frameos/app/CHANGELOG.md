# FrameOS app changes

Applies to `com.wyattfleming.frameos` (`:app`). Modern ARM64 and opt-in legacy
ARMv7 are two build targets of the same package. They share source version fields
and are not interchangeable artifacts. Source `versionName` is not a public
release claim; exact APK hashes and device evidence identify accepted builds.

## Unreleased

- 0.2.19: the remote's return timer can be Never (`idleReturnSeconds` 0), which
  keeps a view up until someone changes it; values between 1 and 29 stay refused.
- 0.2.19: on touchscreen frames the rail's Settings item explains on a tap and opens
  Android settings only on a press and hold.
- Explicit legacy API23/ARMv7 target alongside the default API29/ARM64 target.
- Touch navigation and an immersive Photos/Art menu in the existing shell.
- Separate Art mode and a consolidated Home view with calendar and weather work.
- Home loading, renderer lifecycle and offline behavior improvements under
  device-specific validation.

This entry groups the current development work; it does not certify every
feature on all devices. See [compatibility](../../docs/compatibility.md),
[existing touchscreen guide](../../docs/frameos-touchscreen-panel.md) and
[release gates](../../docs/releasing.md). Earlier cross-component history stays
in the [root changelog](../../CHANGELOG.md); published tags are not rewritten.
