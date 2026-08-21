# Third-party notices

## Immich Kiosk

This project downloads and modifies
[Immich Kiosk](https://github.com/damongolding/immich-kiosk), pinned by default
to release `v0.42.0`.

The default build also verifies upstream commit
`2fc02e0573444de60beff43e33b93f73cf25c2af`; the human-readable tag alone is
not treated as an immutable source pin.

Immich Kiosk is distributed under the GNU Affero General Public License,
version 3. The unmodified upstream license is reproduced in this repository's
`LICENSE` file. Copyright remains with the upstream authors and contributors.

Local modifications are applied as reviewable patch files during the Docker
build. The patched binary identifies its custom version through the
`KIOSK_VERSION` build argument.

The CI audit also fetches this same pinned upstream tree, reapplies the local
patches, and checks the resulting Go and Node dependency licenses before each
scheduled review. One Go module, `github.com/golang/freetype`, is manually
classified as `FTL OR GPL-2.0-or-later` because the automated reporter does not
emit a usable SPDX identifier for it.

Copyright in the original documentation, configuration, and local patches is
held by Wyatt Fleming and contributors, 2026. Published container images carry
OCI source, revision, and AGPL-3.0-only labels; the linked Git tag is the exact
corresponding source for the modified binary. Base-image packages retain their
own copyright and license metadata, and release images include an SBOM and
provenance attestation.

This repository is independent from the Immich Kiosk project. Upstream
maintainers are not responsible for these patches, hardware instructions, or
support.

## Fully Kiosk Browser

Fully Kiosk Browser is referenced as a tested Android renderer but is not
distributed by this repository. Obtain it from its publisher and follow its
license terms. The project also works conceptually with other kiosk-capable
Android WebViews, though they are not currently verified.

## Lenovo and Immich

Lenovo and Immich are trademarks of their respective owners. This project is
not affiliated with or endorsed by Lenovo or the Immich project.
