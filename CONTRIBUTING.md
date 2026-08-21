# Contributing

Small, focused fixes and documentation improvements are welcome. This project
is maintained on a best-effort basis and does not promise support for every
Android frame or Immich release.

## Development workflow

1. Fork and clone the repository.
2. Copy `.env.example` and `config/config.example.yaml` as described in the
   README.
3. Render the Compose file and build the custom image before opening a pull
   request.
4. Explain the tested frame/browser/Immich versions in the pull request.

## Patch discipline

The custom image intentionally carries narrow patches against a pinned upstream
tag. When changing a patch:

- keep upstream behavior unless the change is documented;
- add regression tests in `weighted-curation-tests.patch` or an equivalent
  separate test patch;
- verify the test fails when only the guarded source change is removed;
- verify it passes after restoration;
- do not silently change the upstream pin in the same commit.

If a change is useful to Immich Kiosk generally, consider contributing it
upstream first. Reproduce upstream issues without these local patches before
filing them with upstream maintainers.

## Privacy

Use synthetic, public-domain, or explicitly approved images in issues and pull
requests. Scrub API keys, asset IDs, addresses, people names, and private host
names from logs and screenshots.
