#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/docker-compose.yaml"
ENV_FILE="$ROOT_DIR/.env.example"
CONFIG_FILE="$ROOT_DIR/config/config.yaml"
OPS_FILE="$ROOT_DIR/OPERATIONS.md"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

for required_file in "$COMPOSE_FILE" "$ENV_FILE" "$CONFIG_FILE" "$OPS_FILE"; do
  [[ -f "$required_file" ]] || fail "missing required file: $required_file"
done

grep -Fq 'ghcr.io/tphakala/birdnet-go:20260823@sha256:' "$COMPOSE_FILE" || \
  fail 'BirdNET-Go image must use an immutable release digest'
grep -Eq 'ghcr.io/tphakala/birdnet-go:20260823@sha256:[0-9a-f]{64}' "$COMPOSE_FILE" || \
  fail 'BirdNET-Go image digest must be a complete sha256 digest'
grep -Fq 'BIRDNET_BIND_IP:?Set BIRDNET_BIND_IP' "$COMPOSE_FILE" || \
  fail 'web port must fail closed until a LAN bind address is provided'
grep -Fq 'BIRDNET_CONFIG_DIR:?Set BIRDNET_CONFIG_DIR' "$COMPOSE_FILE" || \
  fail 'config must resolve to an explicit persistent Unraid appdata path'
grep -Fq 'BIRDNET_DATA_DIR:?Set BIRDNET_DATA_DIR' "$COMPOSE_FILE" || \
  fail 'data must resolve to an explicit persistent Unraid appdata path'
grep -Fq 'http://127.0.0.1:8080/health' "$COMPOSE_FILE" || \
  fail 'container healthcheck must use the unauthenticated /health endpoint'
grep -Fq ':/config' "$COMPOSE_FILE" || fail 'config must be persistent'
grep -Fq ':/data' "$COMPOSE_FILE" || fail 'data and clips must be persistent'
grep -Fq '/config/hls:exec,size=50M' "$COMPOSE_FILE" || fail 'HLS segments must use the documented tmpfs'
grep -Fq '/tmp:' "$COMPOSE_FILE" || fail 'read-only root requires writable tmpfs for startup logs'
grep -Fq 'read_only: true' "$COMPOSE_FILE" || fail 'container root filesystem must be read-only'
grep -Fq 'no-new-privileges:true' "$COMPOSE_FILE" || fail 'container must set no-new-privileges'
grep -Fq 'cap_drop:' "$COMPOSE_FILE" || fail 'container must explicitly drop Linux capabilities'
grep -Fq -- '- ALL' "$COMPOSE_FILE" || fail 'container must drop every Linux capability'
grep -Fq 'BIRDNET_BIND_IP=' "$ENV_FILE" || fail 'sample env must document the LAN bind address'
grep -Fq 'WEB_PORT=8090' "$ENV_FILE" || fail 'sample env must use the reserved BirdNET host port'
grep -Fq 'BIRDNET_CONFIG_DIR=/mnt/user/appdata/birdnet-go/config' "$ENV_FILE" || \
  fail 'sample env must keep config off the Unraid boot flash'
grep -Fq 'BIRDNET_DATA_DIR=/mnt/user/appdata/birdnet-go/data' "$ENV_FILE" || \
  fail 'sample env must keep data off the Unraid boot flash'
! grep -Eq '192\.168\.86\.' "$ENV_FILE" || fail 'public sample env must not publish the private LAN address'
grep -Fq 'streams: []' "$CONFIG_FILE" || fail 'sample config must be safe before an RTSP source is installed'
grep -Eq '#[[:space:]]+url: rtsp://[^[:space:]]+' "$CONFIG_FILE" || \
  fail 'sample config must include an explicit RTSP URL placeholder'
grep -Fq 'RTSP_USER' "$CONFIG_FILE" || fail 'RTSP placeholder must show credential indirection'
grep -Fq 'backup' "$OPS_FILE" || fail 'operations notes must cover backups'
grep -Fq 'rollback' "$OPS_FILE" || fail 'operations notes must cover rollback'
grep -Fq '/boot/config/birdnet-go/' "$OPS_FILE" || fail 'operations must use a persistent Unraid Compose project'
grep -Fq '/mnt/user/appdata/birdnet-go/' "$OPS_FILE" || fail 'operations must use Unraid appdata for state'

if command -v docker >/dev/null 2>&1; then
  BIRDNET_BIND_IP=192.0.2.10 docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    config --quiet
else
  printf 'SKIP: docker is not installed; static BirdNET invariants passed\n'
fi

printf 'PASS: BirdNET-Go deployment invariants\n'
