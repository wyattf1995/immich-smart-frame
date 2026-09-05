#!/usr/bin/env bash
# Guarded host loop for the fixed Home Assistant server-health sensor.
set -euo pipefail

config_file=${1:?usage: frame-health-loop.sh /root/frame-health.env}
[[ -r "$config_file" ]] || exit 1
[[ "$(stat -c '%a' "$config_file" 2>/dev/null || stat -f '%Lp' "$config_file" 2>/dev/null)" == "600" ]] || exit 1
# shellcheck disable=SC1090
source "$config_file"
: "${FRAME_HEALTH_PUBLISHER:?}"
: "${FRAME_HEALTH_WATCHDOG:?}"
: "${FRAME_HEALTH_HA_URL:?}"
: "${FRAME_HEALTH_TOKEN_FILE:?}"
: "${BIRDNET_BIND_IP:?}"
: "${BIRDNET_WATCHDOG_AUTH_FILE:?}"

(
  flock -n 9 || exit 0
  while :; do
    if ! /usr/bin/python3 "$FRAME_HEALTH_PUBLISHER" --watchdog "$FRAME_HEALTH_WATCHDOG" --ha-url "$FRAME_HEALTH_HA_URL" --token-file "$FRAME_HEALTH_TOKEN_FILE"; then
      printf '%s\n' 'frame-health publish failed' >&2
    fi
    sleep 60
  done
) 9>/var/run/frame-health-loop.lock
