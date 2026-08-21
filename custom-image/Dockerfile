# Keep the upstream release pinned, then apply the reviewable display,
# curation, dependency-security, input-hardening, and regression-test patches.
FROM alpine/git:v2.54.0@sha256:a299a963fbe31628ab481ca42907bcf0691d004f86734c02638abdf513691d42 AS source

ARG KIOSK_UPSTREAM_REF=v0.42.0
ARG KIOSK_UPSTREAM_COMMIT=2fc02e0573444de60beff43e33b93f73cf25c2af
RUN git clone --depth 1 --branch "${KIOSK_UPSTREAM_REF}" \
    https://github.com/damongolding/immich-kiosk.git /src \
    && test "$(git -C /src rev-parse HEAD)" = "${KIOSK_UPSTREAM_COMMIT}"

COPY fully-kiosk-dpr.patch /tmp/fully-kiosk-dpr.patch
COPY weighted-curation.patch /tmp/weighted-curation.patch
COPY runtime-hardening.patch /tmp/runtime-hardening.patch
COPY weighted-curation-tests.patch /tmp/weighted-curation-tests.patch
RUN cd /src \
    && git apply --check /tmp/fully-kiosk-dpr.patch \
    && git apply /tmp/fully-kiosk-dpr.patch \
    && git apply --check /tmp/weighted-curation.patch \
    && git apply /tmp/weighted-curation.patch \
    && git apply --check --unidiff-zero /tmp/runtime-hardening.patch \
    && git apply --unidiff-zero /tmp/runtime-hardening.patch \
    && git apply --check --unidiff-zero /tmp/weighted-curation-tests.patch \
    && git apply --unidiff-zero /tmp/weighted-curation-tests.patch

FROM node:26-alpine@sha256:aadf416b2cdce311a8811ba3f0608a61b77dbf997500e2eafe781b51f6a0b019 AS frontend-build

ARG GO_TASK_VERSION=3.53.1

COPY --from=source /src /app
WORKDIR /app/frontend

RUN npm install -g "@go-task/cli@${GO_TASK_VERSION}"
RUN npm ci
RUN task frontend

FROM golang:1.26.6-bookworm@sha256:116d58cbd88c1297624acc6e967a060012422bacf9930927e23fb719189c6f36 AS build

ARG KIOSK_VERSION=0.1.0

WORKDIR /app

COPY --from=source /src .
COPY --from=frontend-build /app/frontend/public/assets/css /app/frontend/public/assets/css
COPY --from=frontend-build /app/frontend/public/assets/js/ /app/frontend/public/assets/js/

RUN go mod download
RUN go tool templ generate
RUN CI=1 go test ./...
RUN CGO_ENABLED=0 go build -a -installsuffix cgo \
    -ldflags "-X main.version=${KIOSK_VERSION} -s -w" -o dist/kiosk .

FROM gcr.io/distroless/static-debian13:nonroot@sha256:f7f8f729987ad0fdf6b05eeeae94b26e6a0f613bdf46feea7fc40f7bd72953e6

ENV TZ=Europe/London
ENV TERM=xterm-256color
ENV DEBUG_COLORS=true
ENV COLORTERM=truecolor

WORKDIR /

COPY --from=build /app/demo.config.yaml .
COPY --from=build /app/dist/kiosk .

USER nonroot

ENTRYPOINT ["/kiosk"]
