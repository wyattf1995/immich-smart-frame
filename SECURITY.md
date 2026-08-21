# Security policy

## Scope and supported versions

This is a best-effort personal project, not a commercial service. Security
fixes land on the current `main` branch first. Older tags may not receive
backports, and there is no long-term-support window or response SLA.

## Reporting a vulnerability

Do not include API keys, private photo URLs, LAN details, asset IDs, or
personal images in a public issue. Submit the report through
[GitHub private vulnerability reporting](https://github.com/wyattf1995/immich-smart-frame/security/advisories/new).
If you are not sure whether something is sensitive, default to that private
channel.

Please include:

- affected commit or tag;
- exact deployment shape, including whether the frame is LAN-only or exposed
  through a reverse proxy;
- reproduction steps using synthetic data where possible;
- whether the issue requires non-default API permissions or Android settings.

## Hardening guidance

- Use a dedicated Immich API key with read-only permissions only.
- Keep the key in `secrets/immich_api_key`; never place it in YAML, `.env`, a
  browser URL, screenshot, shell history example, issue, or log excerpt.
- Treat `offline-assets/` as private photo data. It is ignored by Git and must
  not be attached to issues, build artifacts, or releases.
- Keep Kiosk on a trusted network or behind an authenticated reverse proxy. The
  slideshow endpoint itself does not add user authentication.
- Put the Android frame on a dedicated VLAN or isolated SSID when practical.
  Allow only the Docker host's slideshow port and the minimum DNS/NTP egress
  your network requires. Deny lateral access to the rest of your LAN.
- Treat wireless ADB as privileged shell access. Enable it only long enough to
  provision or debug, then run `adb usb`, disconnect the TCP session, and keep
  TCP/5555 blocked by default.
- Review upstream Immich Kiosk releases and local patch applicability before
  changing the pinned version.
- Treat metadata as personal information: capture dates, recognized people, and
  locations can be sensitive even when the image itself is not published.

## What is intentionally out of scope

- response-time guarantees;
- managed hosting, remote administration, or incident response;
- security review for arbitrary third-party kiosk browsers or custom Android
  firmware;
- assurances that a given Immich permission model or Android setting will
  remain unchanged across upstream releases.

The tracked examples contain no live credentials. `.env`, the active config,
`secrets/`, and screenshots are intentionally ignored by Git.
