# Security policy

## Supported versions

This is an experimental personal project. Security fixes are applied to the
current `main` branch; there is no long-term support promise for older tags.

## Reporting a vulnerability

Do not include API keys, private photo URLs, LAN details, or personal images in
a public issue. Use GitHub's private vulnerability-reporting feature once the
repository is published. Until then, contact the maintainer privately.

## Deployment guidance

- Use a dedicated Immich API key with read-only permissions.
- Keep the key in `secrets/immich_api_key`; never place it in YAML, `.env`, a
  URL, screenshot, issue, or log excerpt.
- Keep Kiosk and Immich on a trusted network or behind an authenticated reverse
  proxy. This project does not add authentication to Kiosk.
- Do not expose Android Debug Bridge port 5555 outside a trusted LAN.
- Review upstream Immich Kiosk releases and local patch applicability before
  changing the pinned version.
- Treat metadata as personal information: capture dates, recognized people,
  and locations can be sensitive even when the image itself is not published.

The tracked examples contain no live credentials. `.env`, the active config,
secrets, and screenshots are intentionally ignored by Git.
