# Maintainer release process

Releases are intentionally lightweight: the maintainer verifies the exact
source revision and builds every artifact on a trusted local machine. This
repository has no GitHub Actions workflows and does not publish automatically.

## Prepare

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

## Tag and publish

Create an annotated tag from the verified `main` commit:

```sh
git tag -a vMAJOR.MINOR.PATCH -m "immich-smart-frame vMAJOR.MINOR.PATCH"
```

Build an amd64 release candidate locally and scan it before publishing:

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
