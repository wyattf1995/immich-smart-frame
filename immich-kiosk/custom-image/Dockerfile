# Keep the upstream release pinned, then apply the two narrow local features:
# physical-pixel reporting for high-density Android WebViews and named,
# explicitly weighted source profiles for photo curation.
FROM alpine/git:2.49.1 AS source

ARG KIOSK_UPSTREAM_REF=v0.42.0
RUN git clone --depth 1 --branch "${KIOSK_UPSTREAM_REF}" \
    https://github.com/damongolding/immich-kiosk.git /src

COPY fully-kiosk-dpr.patch /tmp/fully-kiosk-dpr.patch
COPY weighted-curation.patch /tmp/weighted-curation.patch
RUN cd /src \
    && git apply --check /tmp/fully-kiosk-dpr.patch \
    && git apply /tmp/fully-kiosk-dpr.patch \
    && git apply --check /tmp/weighted-curation.patch \
    && git apply /tmp/weighted-curation.patch

FROM node:26-alpine AS frontend-build

COPY --from=source /src /app
WORKDIR /app/frontend

RUN npm install -g @go-task/cli
RUN npm ci
RUN task frontend

FROM golang:1.26.5-bookworm AS build

ARG KIOSK_VERSION=0.42.0-lenovo-curation1

WORKDIR /app

COPY --from=source /src .
COPY --from=frontend-build /app/frontend/public/assets/css /app/frontend/public/assets/css
COPY --from=frontend-build /app/frontend/public/assets/js/ /app/frontend/public/assets/js/

RUN go mod download
RUN go tool templ generate
RUN CGO_ENABLED=0 go build -a -installsuffix cgo \
    -ldflags "-X main.version=${KIOSK_VERSION} -s -w" -o dist/kiosk .

FROM gcr.io/distroless/static-debian13:nonroot

ENV TZ=Europe/London
ENV TERM=xterm-256color
ENV DEBUG_COLORS=true
ENV COLORTERM=truecolor

WORKDIR /

COPY --from=build /app/demo.config.yaml .
COPY --from=build /app/dist/kiosk .

USER nonroot

ENTRYPOINT ["/kiosk"]
