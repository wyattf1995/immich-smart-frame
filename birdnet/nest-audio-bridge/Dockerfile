# syntax=docker/dockerfile:1.7

# go2rtc v1.9.14 is the reviewed release. Its Home Assistant source still
# speaks the pre-2024.11 signaling protocol, so this image carries only the
# focused protocol tests and compatibility fix in patches/.
FROM --platform=$BUILDPLATFORM docker.io/library/golang:1.25-alpine@sha256:1ae0735f00daffa3aaf1363a5184c0d2dc55c78e3db4ec70241cdac97bf84b59 AS build

ARG TARGETOS
ARG TARGETARCH

RUN apk add --no-cache git=2.54.0-r0

WORKDIR /src
RUN git clone --branch v1.9.14 --depth 1 https://github.com/AlexxIT/go2rtc.git . \
    && test "$(git rev-parse HEAD)" = "b5948cfb25404cc5cb37b166ecaa2dca20b11d4b"

COPY patches/0001-test-hass-current-webrtc-api.patch /patches/0001-test-hass-current-webrtc-api.patch
COPY patches/0002-fix-hass-current-webrtc-api.patch /patches/0002-fix-hass-current-webrtc-api.patch

RUN git apply --check /patches/0001-test-hass-current-webrtc-api.patch \
    && git apply /patches/0001-test-hass-current-webrtc-api.patch \
    && git apply --unidiff-zero --check /patches/0002-fix-hass-current-webrtc-api.patch \
    && git apply --unidiff-zero /patches/0002-fix-hass-current-webrtc-api.patch \
    && go test ./pkg/hass

RUN CGO_ENABLED=0 GOOS="$TARGETOS" GOARCH="$TARGETARCH" \
    go build -buildvcs=false -trimpath -ldflags="-s -w" -o /out/go2rtc .

# Reuse the release runtime for its reviewed signal handling, CA roots, and
# diagnostics while replacing only the patched go2rtc binary.
FROM ghcr.io/alexxit/go2rtc:1.9.14@sha256:675c318b23c06fd862a61d262240c9a63436b4050d177ffc68a32710d9e05bae

LABEL org.opencontainers.image.source="https://github.com/wyattf1995/immich-smart-frame" \
      org.opencontainers.image.version="1.9.14-ha2026.8" \
      org.opencontainers.image.licenses="MIT"

COPY --from=build --chmod=0755 /out/go2rtc /usr/local/bin/go2rtc
COPY --from=build --chmod=0444 /src/LICENSE /licenses/go2rtc/LICENSE
COPY --chmod=0444 go2rtc.yaml /etc/go2rtc/go2rtc.yaml

ENV CREDENTIALS_DIRECTORY=/run/secrets

CMD ["go2rtc", "-config", "/etc/go2rtc/go2rtc.yaml"]
