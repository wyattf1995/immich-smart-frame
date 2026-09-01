#!/usr/bin/env bash
# Read-only BirdNET-Go observer. It intentionally never writes or restarts.
set -euo pipefail

: "${BIRDNET_BIND_IP:?Set BIRDNET_BIND_IP to the BirdNET-Go LAN address}"
WEB_PORT="${WEB_PORT:-8088}"
DATA_DIR="${BIRDNET_DATA_DIR:-/mnt/user/appdata/birdnet-go/data}"
MAX_AGE_SECONDS="${BIRDNET_AUDIO_MAX_AGE_SECONDS:-120}"
HEALTH_URL="http://${BIRDNET_BIND_IP}:${WEB_PORT}/api/v2/health/audio"

failed=0

report() {
  printf '%s\n' "$*"
}

fail() {
  report "CRITICAL: $*"
  failed=1
}

require_command() {
  command -v "$1" || {
    fail "required command is unavailable: $1"
    return 1
  }
}

container_restart_count() {
  local container="$1" count
  if ! docker inspect --format '{{.Id}}' "$container"; then
    report "INFO: ${container} RestartCount=not-created"
    return 0
  fi
  count=$(docker inspect --format '{{.RestartCount}}' "$container") || {
    fail "cannot inspect ${container} RestartCount"
    return 1
  }
  report "INFO: ${container} RestartCount=${count}"
}

audio_freshness_seconds() {
  # Accept explicit ages or a real audio-receipt timestamp from the health API.
  # A healthy process without either is not evidence that audio is still fresh.
  jq -er '
    [
      .. | objects |
      (.audio_age_seconds? // .last_audio_age_seconds? // .age_seconds? // empty) |
      select(type == "number")
    ] as $ages |
    [
      .. | objects |
      (.last_audio_at? // .last_audio_time? // .last_audio_timestamp? // empty) |
      if type == "number" then now - .
      elif type == "string" then now - fromdateiso8601
      else empty end
    ] as $timestamps |
    ($ages + $timestamps | map(select(. >= 0)) | min) // empty
  '
}

require_command curl || true
require_command jq || true
require_command docker || true
require_command df || true

if health_json=$(curl --fail --silent --show-error --connect-timeout 3 --max-time 8 "$HEALTH_URL"); then # /api/v2/health/audio
  if freshness=$(printf '%s' "$health_json" | audio_freshness_seconds); then
    if awk -v age="$freshness" -v max="$MAX_AGE_SECONDS" 'BEGIN { exit !(age <= max) }'; then
      report "INFO: audio health fresh (${freshness%.*}s <= ${MAX_AGE_SECONDS}s)"
    else
      fail "audio health is stale (${freshness%.*}s exceeds ${MAX_AGE_SECONDS}s)"
    fi
  else
    fail "audio health response has no usable freshness value"
  fi
else
  fail "audio health endpoint is unavailable: ${HEALTH_URL}"
fi

container_restart_count birdnet-go || true
container_restart_count nest-audio-bridge || true

if disk_line=$(df -Pk "$DATA_DIR" | awk 'NR == 2 { print $4 " KiB available on " $6 }'); then
  report "INFO: disk ${disk_line}"
else
  fail "cannot check disk space for ${DATA_DIR}"
fi

exit "$failed"
