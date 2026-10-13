# Component release process

Releases are intentionally lightweight: the maintainer verifies the exact
source revision and builds every artifact on trusted, admitted infrastructure. This
repository has no GitHub Actions workflows and does not publish automatically.

## Select the component

| Component | Change history | Version and future tag convention | Artifacts |
| --- | --- | --- | --- |
| Browser/backend | [Root changelog](../CHANGELOG.md) | Existing `vMAJOR.MINOR.PATCH` repository/container convention | Kiosk image and matching configuration/patch docs |
| Existing FrameOS | [App changelog](../frameos/app/CHANGELOG.md) | `app/build.gradle.kts`; `frameos-vMAJOR.MINOR.PATCH` for future app releases | Explicit modern ARM64 and/or legacy ARMv7 APK, each separately labeled |
| FrameOS Touch | [Touch changelog](../frameos/touchframe/CHANGELOG.md) | `touchframe/build.gradle.kts`; `touchframe-vMAJOR.MINOR.PATCH` for future Touch releases | Separate Touch APK and its own acceptance results |
| Touch Probe diagnostic | [Probe guide](../frameos/touchprobe/README.md) | Independent `touchprobe/app/build.gradle.kts`; no public release claimed here | Clearly labeled diagnostic APK; never a FrameOS launcher release |

These app tag names are conventions for future releases, not claims that tags
or APKs already exist. Preserve old public tags and mixed changelog history.
`:frame-shared` is an internal library, versioned with its consumers; it has no
independent public SDK release. The diagnostic Probe has a separate Gradle root
and must be labeled as a diagnostic if distributed. Other services, including
the [companion](../companion/README.md), retain their own version metadata.

A release note must name its component, application ID where relevant, exact
source commit, build target/ABI, min/target SDK and artifact SHA-256. Distinguish
compilation/JVM proof, emulator checks, specific physical-device results and
known limitations. An app version string alone cannot identify a tested binary.

## Android app releases

1. Update the chosen app's changelog and intentional version fields. Preserve
   the existing FrameOS rollback `versionCode` pin unless its documented reason
   is disproved; do not change it as routine housekeeping.
2. Fetch and reconcile `origin/staging` before integration. Before publication,
   verify the tested source is the exact reviewed commit and the documented
   integration branch is still its ancestor. Release tags identify commits on
   `main`; development candidates are not releases.
3. Use [app build instructions](android-apps.md) in an admitted Android worker.
   Run compilation, lint, JVM tests and applicable source-only red/green proofs.
   Keep test and guarded implementation changes in separate commits. Shared
   source changes require validation in both affected apps/targets.
4. Retain unsigned APKs, R8 mappings, test/lint reports, dependency and merged
   manifest output, source/toolchain identity and checksums outside Git. Export
   and independently verify artifacts before retiring a build workspace.
5. Sign locally with the intended app's established certificate under controlled
   access. Keep QA and distribution signing distinct. Never put private keys,
   passwords or household configuration into build uploads or release assets.
   Verify the signed artifact's package, version, ABI, SDK, signature and hash.
6. Test the exact signed artifact on its declared devices. Include first launch,
   authentication, touch/keys, Settings return, offline/reconnect, repeated mode
   transitions and restart behavior. Check current-platform permissions and
   native-library/page-size compatibility where applicable. Record unsupported
   combinations in [compatibility](compatibility.md); do not borrow another app's
   acceptance. Touch requires independent sign-in and companion takeover.
7. Publish only intentionally selected, verified APKs and sanitized release
   notes. Keep raw logs, mappings and diagnostic evidence privately retained
   unless each has been reviewed for an explicit public need. Verify uploaded
   bytes, release/tag source and visibility before announcing completion.

Release preparation does not authorize firmware writes or a household rollout.
App installation and [hardware recovery](hardware-recovery.md) are separate.

## Browser/backend releases

The following existing process applies to Kiosk/container releases, not to an
Android APK's version fields or signing identity.

### Prepare

1. Update `CHANGELOG.md`, moving completed entries out of `Unreleased`.
2. Confirm the version is consistent in the example environment, Compose
   defaults, Dockerfile build metadata, and release notes.
3. Fetch the remote and verify `origin/main` is an ancestor of `HEAD`.
4. Run the full local validation and security suite documented in
   `CONTRIBUTING.md`.
5. Run `./scripts/run-gitleaks.sh`, `./scripts/audit-licenses.sh`, and
   `./scripts/run-govulncheck.sh`, then record the commands and results in the
   release notes.
6. Push `main`, fetch again, and confirm the remote did not move before tagging.

### Tag and publish

Create an annotated tag from the verified `main` commit:

```sh
git tag -a vMAJOR.MINOR.PATCH -m "immich-smart-frame vMAJOR.MINOR.PATCH"
```

Build an amd64 release candidate on admitted infrastructure and scan it before publishing:

```sh
docker buildx build \
  --platform linux/amd64 \
  --load \
  --pull \
  --build-arg KIOSK_VERSION=MAJOR.MINOR.PATCH \
  --label org.opencontainers.image.licenses=AGPL-3.0-only \
  --label org.opencontainers.image.source=https://github.com/wyattf1995/immich-smart-frame \
  --label org.opencontainers.image.revision="$(git rev-parse HEAD)" \
  --tag ghcr.io/wyattf1995/immich-smart-frame:release-candidate \
  custom-image
KIOSK_IMAGE=ghcr.io/wyattf1995/immich-smart-frame:release-candidate \
  ./scripts/run-trivy.sh
```

If a GHCR image is part of the release, authenticate from the trusted machine
with a narrowly scoped token, then use `docker buildx build --push` for the
`linux/amd64,linux/arm64` targets with the same labels, version build argument,
SBOM, provenance, and immutable version tag. Never place the token in a command
argument, repository file, or release note.

Only after the scan passes and any image is verified should the maintainer push
the tag:

```sh
git push origin vMAJOR.MINOR.PATCH
```

Create release notes from the matching changelog entry and link the exact
container tag. Verify the GitHub release, GHCR package visibility, manifest
architectures, source label, and vulnerability scan before announcing it.

## Failed release

Do not move an existing public tag. Fix the problem on `main`, document it, and
cut the next patch version. A tag that was pushed accidentally may be deleted
only before anyone has consumed it and must be called out transparently.
