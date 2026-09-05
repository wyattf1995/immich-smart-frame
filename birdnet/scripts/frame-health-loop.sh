#!/usr/bin/env bash
# Guarded host loop for the fixed Home Assistant server-health sensor.
set -euo pipefail

config_file=${1:?usage: frame-health-loop.sh /root/frame-health.env}
[[ -r "$config_file" ]] || exit 1
[[ "$(stat -c '%a' "$config_file" 2>/dev/null || stat -f '%Lp' "$config_file" 2>/dev/null)" == "600" ]] || exit 1
owner=$(stat -c '%u' "$config_file" 2>/dev/null || stat -f '%u' "$config_file" 2>/dev/null) || exit 1
[[ "$owner" == "$(id -u)" || "$owner" == "0" ]] || exit 1
# shellcheck disable=SC1090
source "$config_file"
: "${FRAME_HEALTH_PUBLISHER:?}"
: "${FRAME_HEALTH_WATCHDOG:?}"
: "${FRAME_HEALTH_HA_URL:?}"
: "${FRAME_HEALTH_TOKEN_FILE:?}"
: "${FRAME_HEALTH_CONTAINER_TOKEN_FILE:?}"
: "${FRAME_HEALTH_PYTHON_IMAGE:?}"
: "${BIRDNET_BIND_IP:?}"
: "${BIRDNET_WATCHDOG_AUTH_FILE:?}"
export BIRDNET_BIND_IP BIRDNET_WATCHDOG_AUTH_FILE
if [[ -n "${WEB_PORT:-}" ]]; then
  export WEB_PORT
fi

RUN_OUTPUT=""
RUN_CODE=1
run_bounded() {
  local limit="$1" timeout_seconds="$2"
  shift 2
  local directory fifo worker
  directory=$(mktemp -d) || return 1
  fifo="$directory/output"
  mkfifo "$fifo" || { rmdir "$directory"; return 1; }
  timeout "$timeout_seconds" "$@" >"$fifo" 2>/dev/null &
  worker=$!
  RUN_OUTPUT=$(head -c "$limit" <"$fifo")
  wait "$worker" || RUN_CODE=$?
  RUN_CODE=${RUN_CODE:-0}
  [[ ${#RUN_OUTPUT} -lt "$limit" ]] || RUN_CODE=1
  rm -f "$fifo"
  rmdir "$directory"
}

inspect_report() {
  local container="$1"
  RUN_CODE=0
  run_bounded 512 5 docker inspect --format '{{.State.Running}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.OOMKilled}}|{{.RestartCount}}' "$container"
  jq -cn --argjson returncode "$RUN_CODE" --arg output "$RUN_OUTPUT" '{returncode:$returncode,output:$output}'
}

publish_once() {
  local watchdog evidence now_ms immich birdnet bridge
  RUN_CODE=0
  run_bounded 8192 15 "$FRAME_HEALTH_WATCHDOG"
  watchdog=$(jq -cn --argjson returncode "$RUN_CODE" --arg output "$RUN_OUTPUT" '{returncode:$returncode,output:$output}')
  immich=$(inspect_report immich-kiosk)
  birdnet=$(inspect_report birdnet-go)
  bridge=$(inspect_report nest-audio-bridge)
  now_ms=$(( $(date +%s) * 1000 ))
  evidence=$(jq -cn --argjson observedAt "$now_ms" --argjson watchdog "$watchdog" --argjson immich "$immich" --argjson birdnet "$birdnet" --argjson bridge "$bridge" \
    '{observedAt:$observedAt,watchdog:$watchdog,containers:{"immich-kiosk":$immich,"birdnet-go":$birdnet,"nest-audio-bridge":$bridge}}')
  printf '%s' "$evidence" | docker run --rm --network host --read-only --cap-drop ALL --security-opt no-new-privileges --memory 64m --pids-limit 32 --cpus .1 --tmpfs /tmp:size=8m \
    --mount "type=bind,src=$FRAME_HEALTH_PUBLISHER,dst=/publisher.py,readonly" \
    --mount "type=bind,src=$FRAME_HEALTH_TOKEN_FILE,dst=$FRAME_HEALTH_CONTAINER_TOKEN_FILE,readonly" \
    "$FRAME_HEALTH_PYTHON_IMAGE" python -B /publisher.py --evidence-stdin --ha-url "$FRAME_HEALTH_HA_URL" --token-file "$FRAME_HEALTH_CONTAINER_TOKEN_FILE" >/dev/null 2>/dev/null
}

(
  flock -n 9 || exit 0
  child_pid=""
  cleanup() {
    if [[ -n "$child_pid" ]]; then
      kill "$child_pid" 2>/dev/null || true
      wait "$child_pid" 2>/dev/null || true
    fi
    exit 0
  }
  trap cleanup INT TERM
  failed=0
  while :; do
    publish_once &
    child_pid=$!
    if wait "$child_pid"; then
      if (( failed )); then
        printf '%s\n' 'frame-health publish recovered' >&2
        failed=0
      fi
    elif (( ! failed )); then
      printf '%s\n' 'frame-health publish failed' >&2
      failed=1
    fi
    child_pid=""
    sleep 60 &
    child_pid=$!
    wait "$child_pid" || true
    child_pid=""
  done
) 9>/var/run/frame-health-loop.lock
