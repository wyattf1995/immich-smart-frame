# syntax=docker/dockerfile:1.7
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
COPY album-penalties.patch /tmp/album-penalties.patch
COPY backend-performance.patch /tmp/backend-performance.patch
COPY backend-registry-final.patch /tmp/backend-registry-final.patch
COPY backend-registry-allocation.patch /tmp/backend-registry-allocation.patch
COPY backend-source-gofmt.patch /tmp/backend-source-gofmt.patch
COPY weighted-curation-tests.patch /tmp/weighted-curation-tests.patch
COPY performance-regression-tests.patch /tmp/performance-regression-tests.patch
COPY strict-backend-regression-tests.patch /tmp/strict-backend-regression-tests.patch
COPY date-pool-concurrency-tests.patch /tmp/date-pool-concurrency-tests.patch
COPY backend-test-fixtures.patch /tmp/backend-test-fixtures.patch
COPY album-registry-tests.patch /tmp/album-registry-tests.patch
COPY backend-hard-timeout-tests.patch /tmp/backend-hard-timeout-tests.patch
COPY date-pool-exhaustion-tests.patch /tmp/date-pool-exhaustion-tests.patch
COPY album-registry-allocation-tests.patch /tmp/album-registry-allocation-tests.patch
COPY backend-test-gofmt.patch /tmp/backend-test-gofmt.patch
COPY resilience-backend-tests.patch /tmp/resilience-backend-tests.patch
COPY resilience-backend-coverage-tests.patch /tmp/resilience-backend-coverage-tests.patch
COPY resilience-backend-m2-tests.patch /tmp/resilience-backend-m2-tests.patch
COPY resilience-offline-prewarm-tests.patch /tmp/resilience-offline-prewarm-tests.patch
COPY resilience-lifecycle-tests.patch /tmp/resilience-lifecycle-tests.patch
COPY resilience-timeout-prewarm-tests.patch /tmp/resilience-timeout-prewarm-tests.patch
COPY resilience-timeout-bounds-tests.patch /tmp/resilience-timeout-bounds-tests.patch
COPY readiness-selection-tests.patch /tmp/readiness-selection-tests.patch
COPY readiness-selection-array-tests.patch /tmp/readiness-selection-array-tests.patch
COPY readiness-root-fixture-tests.patch /tmp/readiness-root-fixture-tests.patch
COPY slideshow-recovery-tests.patch /tmp/slideshow-recovery-tests.patch
COPY resilience-backend.patch /tmp/resilience-backend.patch
COPY slideshow-recovery.patch /tmp/slideshow-recovery.patch
RUN cd /src \
    && git apply --check /tmp/fully-kiosk-dpr.patch \
    && git apply /tmp/fully-kiosk-dpr.patch \
    && git apply --check /tmp/weighted-curation.patch \
    && git apply /tmp/weighted-curation.patch \
    && git apply --check --unidiff-zero /tmp/runtime-hardening.patch \
    && git apply --unidiff-zero /tmp/runtime-hardening.patch \
    && git apply --check /tmp/album-penalties.patch \
    && git apply /tmp/album-penalties.patch \
	&& git apply --check --unidiff-zero /tmp/backend-performance.patch \
	&& git apply --unidiff-zero /tmp/backend-performance.patch \
	&& git apply --check --unidiff-zero /tmp/backend-registry-final.patch \
	&& git apply --unidiff-zero /tmp/backend-registry-final.patch \
	&& git apply --check --unidiff-zero /tmp/backend-registry-allocation.patch \
	&& git apply --unidiff-zero /tmp/backend-registry-allocation.patch \
	&& git apply --check --unidiff-zero /tmp/backend-source-gofmt.patch \
	&& git apply --unidiff-zero /tmp/backend-source-gofmt.patch \
    && git apply --check --unidiff-zero /tmp/weighted-curation-tests.patch \
    && git apply --unidiff-zero /tmp/weighted-curation-tests.patch \
    && git apply --check --unidiff-zero /tmp/performance-regression-tests.patch \
    && git apply --unidiff-zero /tmp/performance-regression-tests.patch \
    && git apply --check --unidiff-zero /tmp/strict-backend-regression-tests.patch \
    && git apply --unidiff-zero /tmp/strict-backend-regression-tests.patch \
	&& git apply --check --unidiff-zero /tmp/date-pool-concurrency-tests.patch \
	&& git apply --unidiff-zero /tmp/date-pool-concurrency-tests.patch \
	&& git apply --check --unidiff-zero /tmp/backend-test-fixtures.patch \
	&& git apply --unidiff-zero /tmp/backend-test-fixtures.patch \
	&& git apply --check --unidiff-zero /tmp/album-registry-tests.patch \
	&& git apply --unidiff-zero /tmp/album-registry-tests.patch \
	&& git apply --check --unidiff-zero /tmp/backend-hard-timeout-tests.patch \
	&& git apply --unidiff-zero /tmp/backend-hard-timeout-tests.patch \
	&& git apply --check --unidiff-zero /tmp/date-pool-exhaustion-tests.patch \
	&& git apply --unidiff-zero /tmp/date-pool-exhaustion-tests.patch \
	&& git apply --check --unidiff-zero /tmp/album-registry-allocation-tests.patch \
	&& git apply --unidiff-zero /tmp/album-registry-allocation-tests.patch \
	&& git apply --check --unidiff-zero /tmp/backend-test-gofmt.patch \
	&& git apply --unidiff-zero /tmp/backend-test-gofmt.patch \
	&& git apply --check --unidiff-zero /tmp/resilience-backend-tests.patch \
	&& git apply --unidiff-zero /tmp/resilience-backend-tests.patch \
	&& git apply --check --unidiff-zero /tmp/resilience-backend-coverage-tests.patch \
	&& git apply --unidiff-zero /tmp/resilience-backend-coverage-tests.patch \
	&& git apply --check --unidiff-zero /tmp/resilience-backend-m2-tests.patch \
	&& git apply --unidiff-zero /tmp/resilience-backend-m2-tests.patch \
	&& git apply --check --unidiff-zero /tmp/resilience-offline-prewarm-tests.patch \
	&& git apply --unidiff-zero /tmp/resilience-offline-prewarm-tests.patch \
	&& git apply --check --unidiff-zero /tmp/resilience-lifecycle-tests.patch \
	&& git apply --unidiff-zero /tmp/resilience-lifecycle-tests.patch \
	&& git apply --check --unidiff-zero /tmp/resilience-timeout-prewarm-tests.patch \
	&& git apply --unidiff-zero /tmp/resilience-timeout-prewarm-tests.patch \
	&& git apply --check --unidiff-zero /tmp/resilience-timeout-bounds-tests.patch \
	&& git apply --unidiff-zero /tmp/resilience-timeout-bounds-tests.patch \
	&& git apply --check --unidiff-zero /tmp/readiness-selection-tests.patch \
	&& git apply --unidiff-zero /tmp/readiness-selection-tests.patch \
	&& git apply --check --unidiff-zero /tmp/readiness-selection-array-tests.patch \
	&& git apply --unidiff-zero /tmp/readiness-selection-array-tests.patch \
	&& git apply --check --unidiff-zero /tmp/readiness-root-fixture-tests.patch \
	&& git apply --unidiff-zero /tmp/readiness-root-fixture-tests.patch \
	&& git apply --check --unidiff-zero /tmp/slideshow-recovery-tests.patch \
	&& git apply --unidiff-zero /tmp/slideshow-recovery-tests.patch \
	&& git apply --check --unidiff-zero /tmp/resilience-backend.patch \
	&& git apply --unidiff-zero /tmp/resilience-backend.patch \
	&& git apply --check --unidiff-zero /tmp/slideshow-recovery.patch \
	&& git apply --unidiff-zero /tmp/slideshow-recovery.patch

FROM node:26-alpine@sha256:aadf416b2cdce311a8811ba3f0608a61b77dbf997500e2eafe781b51f6a0b019 AS frontend-build

ARG GO_TASK_VERSION=3.53.1

COPY --from=source /src /app
WORKDIR /app/frontend

RUN npm install -g "@go-task/cli@${GO_TASK_VERSION}"
RUN --mount=type=cache,target=/root/.npm npm ci
RUN node_modules/.bin/esbuild tests/asset-request-recovery.test.ts \
      --bundle --platform=node --format=esm \
      --outfile=/tmp/asset-request-recovery.test.mjs \
    && node --test /tmp/asset-request-recovery.test.mjs \
    && node_modules/.bin/esbuild tests/polling-recovery.test.ts \
      --bundle --platform=node --format=esm \
      --alias:htmx.org=./tests/htmx.mock.ts \
      --outfile=/tmp/polling-recovery.test.mjs \
    && node --test /tmp/polling-recovery.test.mjs
RUN task frontend

FROM golang:1.26.6-bookworm@sha256:116d58cbd88c1297624acc6e967a060012422bacf9930927e23fb719189c6f36 AS build

ARG KIOSK_VERSION=0.1.0

WORKDIR /app

COPY --from=source /src .
COPY --from=frontend-build /app/frontend/public/assets/css /app/frontend/public/assets/css
COPY --from=frontend-build /app/frontend/public/assets/js/ /app/frontend/public/assets/js/

RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build go mod download
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build go tool templ generate && CI=1 go test ./...
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build CGO_ENABLED=0 go build -a -installsuffix cgo \
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
