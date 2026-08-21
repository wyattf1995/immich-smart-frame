# Maintainer release process

Releases are intentionally lightweight: the maintainer verifies the exact
source revision locally, pushes an annotated Semantic Versioning tag, and lets
the tag-only GitHub workflow build the container artifact.

## Prepare

1. Update `CHANGELOG.md`, moving completed entries out of `Unreleased`.
2. Confirm the version is consistent in the example environment, Compose
   defaults, Dockerfile build metadata, and release notes.
3. Fetch the remote and verify `origin/main` is an ancestor of `HEAD`.
4. Run the full local validation and security suite documented in
   `CONTRIBUTING.md`.
5. Push `main` and wait for every required `Validate` job to pass.

## Tag and publish

Create and push an annotated tag from the verified `main` commit:

```sh
git tag -a vMAJOR.MINOR.PATCH -m "immich-smart-frame vMAJOR.MINOR.PATCH"
git push origin vMAJOR.MINOR.PATCH
```

The tag-only `Release` workflow builds and scans an amd64 candidate, then
publishes a multi-architecture GHCR image with OCI source/license/revision
labels, an SBOM, and provenance. Do not create the GitHub release until that
workflow is green.

Create release notes from the matching changelog entry and link the exact
container tag. Verify the GitHub release, GHCR package visibility, manifest
architectures, source label, and vulnerability scan before announcing it.

## Failed release

Do not move an existing public tag. Fix the problem on `main`, document it, and
cut the next patch version. A tag that was pushed accidentally may be deleted
only before anyone has consumed it and must be called out transparently.
