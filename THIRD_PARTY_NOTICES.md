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

## BirdNET-Go

The optional deployment package references the unmodified
[BirdNET-Go](https://github.com/tphakala/birdnet-go) container release
`20260823` by its immutable multi-platform manifest digest. BirdNET-Go is not
vendored, modified, or redistributed by this repository; Docker retrieves it
from the upstream GitHub Container Registry when an operator explicitly pulls
the service.

BirdNET-Go is licensed under Creative Commons Attribution-NonCommercial-
ShareAlike 4.0 International. Its code, models, taxonomy data, images, and other
third-party inputs retain their respective upstream terms. Review those terms
before any use beyond this project's personal, noncommercial home deployment.
BirdNET-Go's maintainers are not responsible for FrameOS, this Compose package,
or Lenovo hardware support.

## NGINX Unprivileged

The optional BirdNET frame view references the unmodified
[NGINX Unprivileged](https://github.com/nginx/docker-nginx-unprivileged)
container image `1.31.3-alpine3.24` by its immutable multi-platform manifest
digest. The image is not vendored or modified by this repository; Docker
retrieves it from the upstream GitHub Container Registry when an operator
explicitly pulls the service.

The NGINX Unprivileged container project is licensed under Apache License 2.0.
NGINX and the Alpine base packages retain their respective upstream copyright
and license terms. Their maintainers are not responsible for this project's
kiosk page, proxy policy, deployment configuration, or hardware support.

## go2rtc

The optional Nest audio bridge builds from
[go2rtc](https://github.com/AlexxIT/go2rtc) release `v1.9.14`, pinned to commit
`b5948cfb25404cc5cb37b166ecaa2dca20b11d4b`, and uses the matching upstream
runtime image by immutable manifest digest. This repository applies focused
Home Assistant WebRTC subscription tests and a compatibility patch before
building the replacement binary. The patch is local to this project; upstream
maintainers are not responsible for it or for the Nest/BirdNET deployment.

go2rtc is distributed under the MIT License:

> Copyright (c) 2022 Alexey Khit
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

## Fully Kiosk Browser

Fully Kiosk Browser is referenced as a tested Android renderer but is not
distributed by this repository. Obtain it from its publisher and follow its
license terms. The project also works conceptually with other kiosk-capable
Android WebViews, though they are not currently verified.

## Lenovo and Immich

Lenovo and Immich are trademarks of their respective owners. This project is
not affiliated with or endorsed by Lenovo or the Immich project.
