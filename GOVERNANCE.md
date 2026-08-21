# Governance

## Project model

This repository is maintained by a single maintainer. It is an open-source
personal infrastructure project, not a foundation, company product, or managed
service.

The maintainer is responsible for:

- deciding roadmap and scope;
- merging or closing pull requests;
- cutting tags and release notes;
- deciding whether a change should be carried as a local patch or proposed
  upstream;
- handling security disclosures and moderation under the
  [Code of Conduct](CODE_OF_CONDUCT.md).

## Decision-making

Discussion and pull requests are welcome, but merge authority remains with the
maintainer. Consensus is useful input, not a binding process.

The maintainer will generally prefer changes that:

- preserve read-only treatment of the photo library;
- keep credentials out of URLs, screenshots, and tracked config;
- stay narrow against the pinned upstream Immich Kiosk release;
- come with reproducible validation evidence.

## Release policy

- Tagged releases follow [Semantic Versioning](https://semver.org/).
- Until `1.0.0`, the project remains pre-stable. Behavior can still evolve
  quickly, but breaking changes will be called out in release notes and
  [CHANGELOG.md](CHANGELOG.md).
- A release tag covers the repository as a whole: docs, patch set, tested
  upstream pin, and validation expectations.
- Breaking changes may use a `vMAJOR.MINOR.PATCH-rc.N` release candidate once
  outside users depend on the project. A formal RC cycle is intentionally not
  required while the project has no external release consumers.
- `main` is the active development branch and may move ahead of the latest tag.

## Support expectations

- There is no uptime guarantee, compatibility guarantee, or response-time SLA.
- Older tags may not receive backported fixes.
- The maintainer may pause work, archive the repository, or decline features
  that increase long-term maintenance burden.

## Issue triage

Issues are reviewed on a best-effort basis. The maintainer may close an issue
that cannot be reproduced, is outside project scope, or has been waiting on
requested information. Automated stale closure is intentionally disabled while
issue volume is low.

## Contribution boundaries

By contributing, you agree that your contribution is licensed under the same
AGPL-3.0 terms as the repository itself. See [CONTRIBUTING.md](CONTRIBUTING.md)
for contributor expectations and privacy rules.
