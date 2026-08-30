# Contributing

Small, focused fixes and documentation improvements are welcome. This is a
solo-maintainer, best-effort project with no support SLA and no promise that
every Android frame, browser build, or Immich release will be supported.

## Before you open a pull request

1. Fork and clone the repository.
2. Follow the fresh-clone setup in [README.md](README.md).
3. Keep changes narrow and explain the user-visible reason for them.
4. Run `./scripts/validate.sh --static` at minimum. Run the full
   `./scripts/validate.sh` when your change affects build, patch, or runtime
   behavior.
5. State the exact versions you tested: frame model, Android version, browser
   version, Immich version, and Immich Kiosk upstream pin if relevant.

This repository does not use GitHub Actions. Contributors and maintainers run
the documented validation and security gates on a trusted local machine.
Dependency updates are reviewed and opened manually; Dependabot version updates
are also disabled because GitHub implements them as hosted Actions jobs.

## Inbound equals outbound

This repository is licensed under the GNU Affero General Public License v3.0.
By submitting a pull request, issue attachment, or documentation patch, you
agree that your contribution is provided under the same AGPL-3.0 terms that
apply to the rest of the repository. No separate contributor license agreement
is required.

## Patch discipline

The custom image intentionally carries narrow patches against a pinned upstream
Immich Kiosk release. When changing a patch:

- keep upstream behavior unless the change is documented here;
- add or update regression coverage in
  `custom-image/weighted-curation-tests.patch` or an equivalent dedicated test
  patch;
- verify the relevant test fails when the guarded source change is removed;
- verify it passes again after restoration;
- do not silently change the upstream pin in the same commit as behavioral
  changes.

If a change is broadly useful to Immich Kiosk, prefer contributing it upstream
or linking the upstream issue in your pull request. Reproduce upstream issues
without these local patches before filing them against upstream maintainers.

## Documentation and privacy rules

- Do not commit `.env`, `config/config.yaml`, `secrets/`, `offline-assets/`,
  screenshots, or any other local deployment artifact.
- Use synthetic, public-domain, or explicitly approved images in issues and
  pull requests.
- Scrub API keys, asset IDs, names, addresses, LAN hostnames, and private photo
  URLs from logs, screenshots, and examples.
- Do not attach personal-family images to issues by default. Describe the
  behavior first; if a maintainer asks for an example, crop and redact it.

## Review expectations

Maintainer review is discretionary and may be slow. A pull request can be
closed if it:

- expands scope beyond the project's documented goals;
- increases credential or privacy risk for a display-only deployment;
- changes upstream-patch behavior without corresponding tests;
- targets unsupported infrastructure without clear validation evidence.
