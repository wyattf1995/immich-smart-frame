#!/usr/bin/env bash
# Read-only Docker/cgroup-v2 sampling. Keep output on persistent storage.
set -euo pipefail
if [[ $# -ne 2 ]]; then
  printf 'usage: %s OUTPUT_JSONL DURATION_SECONDS\n' "$0" >&2
  exit 2
fi
output=$1
duration=$2
[[ "$duration" =~ ^[0-9]+$ ]] && (( duration > 0 && duration <= 172800 ))
container=${KIOSK_CONTAINER:-immich-kiosk}
metrics_url=${KIOSK_METRICS_URL:-http://127.0.0.1:3000/metrics}
interval=${KIOSK_SAMPLE_SECONDS:-60}
[[ "$interval" =~ ^[0-9]+$ ]] && (( interval >= 10 && interval <= 3600 ))
command -v jq >/dev/null
command -v flock >/dev/null
umask 077
exec 9>"$output.lock"
flock -n 9 || exit 1
end=$(( $(date +%s) + duration ))
while (( $(date +%s) < end )); do
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  if state=$(docker inspect --format '{"id":"{{.Id}}","pid":{{.State.Pid}},"running":{{.State.Running}},"restarts":{{.RestartCount}},"oomKilled":{{.State.OOMKilled}},"startedAt":"{{.State.StartedAt}}","health":"{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}"}' "$container" 2>/dev/null); then
    pid=$(jq -r .pid <<<"$state")
    memory=null
    oom=null
    if [[ -r "/proc/$pid/cgroup" ]]; then
      group=$(awk -F: '$1 == "0" { print $3 }' "/proc/$pid/cgroup")
      cg="/sys/fs/cgroup$group"
      if [[ -r "$cg/memory.current" ]]; then memory=$(cat "$cg/memory.current"); fi
      if [[ -r "$cg/memory.events" ]]; then oom=$(awk '$1 == "oom_kill" { print $2 }' "$cg/memory.events"); fi
    fi
    metrics='{}'
    if [[ -n "${KIOSK_METRICS_TOKEN_FILE:-}" && -r "$KIOSK_METRICS_TOKEN_FILE" ]]; then
      token=$(<"$KIOSK_METRICS_TOKEN_FILE")
      # A validated header value is sent through stdin, never curl's process args.
      if [[ "$token" =~ ^[A-Za-z0-9_-]{32,}$ ]]; then
        raw=$(printf 'header = "Authorization: Bearer %s"\n' "$token" | curl --config - --silent --fail --max-time 8 "$metrics_url" 2>/dev/null || true)
        metrics=$(printf '%s\n' "$raw" | jq -ce 'if type == "object" then with_entries(select((.key | test("^(cache_bytes|cache_evictions_total|cache_lock_wait_p95_ns|cache_lock_hold_p95_ns|cache_lock_samples_total|image_work_admissions_total|image_work_wait_nanoseconds_total|image_work_render_nanoseconds_total|go_heap_alloc_bytes|go_heap_sys_bytes|date_pool_widenings_total|date_pool_last_effective_days)$")) and (.value | type == "number"))) else {} end' 2>/dev/null || printf '{}')
      fi
      unset token
    fi
    jq -cn --arg at "$now" --argjson state "$state" --argjson memory "$memory" --argjson oom "$oom" --argjson metrics "$metrics" '{at:$at,container:$state,memoryBytes:$memory,oomKills:$oom,metrics:$metrics}' >>"$output"
  else
    jq -cn --arg at "$now" '{at:$at,error:"container unavailable"}' >>"$output"
  fi
  sleep "$interval"
done
jq -cn --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '{at:$at,complete:true}' >>"$output"
