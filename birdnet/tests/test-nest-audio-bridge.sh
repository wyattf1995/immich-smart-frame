#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/docker-compose.yaml"
ENV_FILE="$ROOT_DIR/.env.example"
BRIDGE_DIR="$ROOT_DIR/nest-audio-bridge"
DOCKERFILE="$BRIDGE_DIR/Dockerfile"
BRIDGE_CONFIG="$BRIDGE_DIR/go2rtc.yaml"
TEST_PATCH="$BRIDGE_DIR/patches/0001-test-hass-current-webrtc-api.patch"
SOURCE_PATCH="$BRIDGE_DIR/patches/0002-fix-hass-current-webrtc-api.patch"
OPS_FILE="$ROOT_DIR/OPERATIONS.md"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

for required_file in \
  "$COMPOSE_FILE" \
  "$ENV_FILE" \
  "$DOCKERFILE" \
  "$BRIDGE_CONFIG" \
  "$TEST_PATCH" \
  "$SOURCE_PATCH" \
  "$OPS_FILE"; do
  [[ -f "$required_file" ]] || fail "missing required file: $required_file"
done

grep -Fq 'b5948cfb25404cc5cb37b166ecaa2dca20b11d4b' "$DOCKERFILE" || \
  fail 'bridge build must pin the reviewed go2rtc v1.9.14 source commit'
grep -Eq 'golang:1\.25-alpine@sha256:[0-9a-f]{64}' "$DOCKERFILE" || \
  fail 'Go builder image must use an immutable digest'
grep -Fq 'ghcr.io/alexxit/go2rtc:1.9.14@sha256:675c318b23c06fd862a61d262240c9a63436b4050d177ffc68a32710d9e05bae' "$DOCKERFILE" || \
  fail 'bridge runtime must use the reviewed immutable go2rtc release image'
grep -Fq '0001-test-hass-current-webrtc-api.patch' "$DOCKERFILE" || \
  fail 'bridge image must apply the current Home Assistant protocol tests'
grep -Fq '0002-fix-hass-current-webrtc-api.patch' "$DOCKERFILE" || \
  fail 'bridge image must apply the current Home Assistant signaling fix'
grep -Fq 'go test ./pkg/hass' "$DOCKERFILE" || \
  fail 'bridge image must run the focused signaling tests before building'
grep -Fq 'TestExchangeSDPUsesCurrentHomeAssistantSubscription' "$TEST_PATCH" || \
  fail 'upstream patch must cover the current Home Assistant offer subscription'
grep -Fq 'TestClientKeepsHomeAssistantSubscriptionUntilStop' "$TEST_PATCH" || \
  fail 'upstream patch must cover subscription lifetime'
grep -Fq 'camera/webrtc/offer' "$SOURCE_PATCH" || \
  fail 'source patch must use the current Home Assistant WebRTC command'

grep -Fq 'listen: ""' "$BRIDGE_CONFIG" || \
  fail 'go2rtc web/API listener must be disabled'
grep -Fq 'hass://${HA_HOST}?entity_id=${HA_CAMERA_ENTITY}&token=${HA_TOKEN}' "$BRIDGE_CONFIG" || \
  fail 'camera source must keep the Home Assistant credential indirect'
grep -Fq 'ffmpeg:nest_camera#audio=pcm/48000' "$BRIDGE_CONFIG" || \
  fail 'BirdNET endpoint must be mono 48 kHz linear PCM'
! grep -R -Eq '192\.168\.86\.|eyJ[A-Za-z0-9_-]{20,}' "$BRIDGE_DIR" || \
  fail 'bridge package must not contain a private LAN address or access token'

grep -Fq 'HA_HOST=192.0.2.30' "$ENV_FILE" || \
  fail 'sample environment must use a documentation-only Home Assistant address'
grep -Fq 'HA_CAMERA_ENTITY=camera.backyard_camera' "$ENV_FILE" || \
  fail 'sample environment must document the single selected camera entity'
grep -Fq 'HA_TOKEN_FILE=/mnt/user/appdata/birdnet-go/secrets/HA_TOKEN' "$ENV_FILE" || \
  fail 'sample environment must keep the Home Assistant token in appdata'
! grep -Eq '^HA_TOKEN=' "$ENV_FILE" || \
  fail 'Home Assistant tokens must never be supplied as environment variables'

grep -Fq 'rtsp://nest-audio-bridge:8554/bird_audio?audio' "$OPS_FILE" || \
  fail 'operations must document the private BirdNET audio endpoint'
grep -Fq 'camera.backyard_backyard_camera' "$OPS_FILE" || \
  fail 'operations must identify the selected live Backyard entity'
grep -Fq 'Garage' "$OPS_FILE" || \
  fail 'operations must document Garage as a manual fallback, not a second active stream'
grep -Fq '12-minute' "$OPS_FILE" || \
  fail 'operations must require a soak test beyond two Nest session windows'

if command -v docker >/dev/null 2>&1; then
  compose_json=$(BIRDNET_BIND_IP=192.0.2.10 docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    --profile nest-audio \
    config --format json)

  python3 - "$compose_json" <<'PY'
import json
import sys

config = json.loads(sys.argv[1])
service = config["services"]["nest-audio-bridge"]

assert service.get("profiles") == ["nest-audio"], service.get("profiles")
assert not service.get("ports"), service.get("ports")
assert service.get("read_only") is True, service.get("read_only")
assert service.get("cap_drop") == ["ALL"], service.get("cap_drop")
assert "no-new-privileges:true" in service.get("security_opt", []), service.get("security_opt")
assert service.get("environment", {}).get("CREDENTIALS_DIRECTORY") == "/run/secrets"
assert service.get("networks") == {"nest-audio": None}, service.get("networks")
assert service.get("secrets") == [{"source": "ha_token", "target": "HA_TOKEN"}], service.get("secrets")
# The bridge needs outbound access to Home Assistant and Nest ICE relays. It is
# still private because no service port is published and only BirdNET attaches.
assert config["networks"]["nest-audio"].get("internal") is not True

birdnet_networks = config["services"]["birdnet-go"].get("networks", {})
assert "default" in birdnet_networks and "nest-audio" in birdnet_networks, birdnet_networks
PY
else
  printf 'SKIP: docker is not installed; static Nest audio bridge invariants passed\n'
fi

printf 'PASS: Nest camera audio bridge invariants\n'
