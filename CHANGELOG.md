# Changelog

All notable changes to this project will be documented in this file.

This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
formatting and [Semantic Versioning](https://semver.org/) for tagged releases.
Until `1.0.0`, releases remain pre-stable; breaking deployment or configuration
changes will still be called out explicitly.

## [Unreleased]

### Added

- Added validated, per-profile album penalty factors so an overrepresented
  album can remain eligible while being selected less often through any source.

### Changed

- Increased the advanced Qwen `balanced` example's overlapping recency share
  from 50% to 65%, with an explicit validation guard for the 35/20/10 ladder.
- Documented how to boost a private milestone album without publishing its
  Immich identifier or reducing the slideshow to one album.

## [0.1.0] - 2026-08-21

### Added

- Native-resolution requests for high-density Android WebViews.
- Named weighted curation profiles, overlapping recency pools, Qwen-tag
  examples, memories, people, albums, tags, and date sources.
- Full capture-date and optional city/state metadata with a 45-second default
  slide duration.
- Open-source contributor, governance, security, upgrade/rollback, and release
  documentation plus privacy-safe issue and pull-request templates.
- Fork-safe CI for static validation, full history secret scanning, dependency
  license auditing, Govulncheck, complete upstream tests, and Trivy scanning.
- Tag-only multi-architecture GHCR publishing with SBOM and provenance.

### Changed

- Pinned Immich Kiosk v0.42.0 to its exact commit and pinned all container base
  images and build tools.
- Raised Go to 1.26.6 and `golang.org/x/image` to 0.45.0.
- Added non-root read-only container hardening, dropped capabilities, and
  bounded client-requested image dimensions to an 8K-safe pixel budget.

### Security

- Added a working private vulnerability-reporting path, dedicated secret-file
  setup, offline-photo-cache exclusions, Android VLAN guidance, and wireless
  ADB shutdown instructions.
- Removed personal commit metadata and private deployment configuration from
  reachable public history before tagging this release.
