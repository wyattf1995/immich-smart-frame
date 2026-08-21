# Third-party notices

## Immich Kiosk

This project downloads and modifies
[Immich Kiosk](https://github.com/damongolding/immich-kiosk), pinned by default
to release `v0.42.0`.

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
