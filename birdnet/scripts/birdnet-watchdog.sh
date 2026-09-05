#!/usr/bin/env bash
# Read-only BirdNET-Go observer. It intentionally never writes or restarts.
set -euo pipefail

: "${BIRDNET_BIND_IP:?Set BIRDNET_BIND_IP to the BirdNET-Go LAN address}"
WEB_PORT="${WEB_PORT:-8090}"
DATA_DIR="${BIRDNET_DATA_DIR:-/mnt/user/appdata/birdnet-go/data}"
MAX_AGE_SECONDS="${BIRDNET_AUDIO_MAX_AGE_SECONDS:-120}"
HEALTH_URL="http://${BIRDNET_BIND_IP}:${WEB_PORT}/api/v2/health/audio"
AUTH_FILE="${BIRDNET_WATCHDOG_AUTH_FILE:-}"
AUTH_LOGIN_MAX_BYTES=16384
AUTH_CALLBACK_HEADER_MAX_BYTES=8192
AUTH_HEALTH_MAX_BYTES=65536
AUTH_CALLBACK_MAX_BYTES=2048

failed=0

report() {
  printf '%s\n' "$*"
}

fail() {
  report "CRITICAL: $*"
  failed=1
}

require_command() {
  command -v "$1" >/dev/null || {
    fail "required command is unavailable: $1"
    return 1
  }
}

container_restart_count() {
  local container="$1" count
  if ! docker inspect --format '{{.Id}}' "$container" >/dev/null; then
    report "INFO: ${container} RestartCount=not-created"
    return 0
  fi
  count=$(docker inspect --format '{{.RestartCount}}' "$container") || {
    fail "cannot inspect ${container} RestartCount"
    return 1
  }
  report "INFO: ${container} RestartCount=${count}"
}

numeric_audio_freshness_seconds() {
  # Accept an explicit age when the health API supplies one.
  jq -er '
    [
      .. | objects |
      (.audio_age_seconds? // .last_audio_age_seconds? // .age_seconds? // empty) |
      select(type == "number")
    ] | map(select(. >= 0)) | min // empty
  '
}

audio_dispatch_timestamps() {
  # last_dispatch is the live BirdNET-Go source receipt timestamp. Retain the
  # other timestamp names for compatible API versions.
  jq -r '
    .. | objects |
    (.last_dispatch? // .last_audio_at? // .last_audio_time? // .last_audio_timestamp? // empty) |
    select(type == "string")
  '
}

epoch_from_timestamp() {
  local timestamp="$1" base zone
  # GNU date handles RFC 3339 fractional seconds and numeric offsets directly.
  if [[ "$(uname -s)" == "Linux" ]]; then
    date -d "$timestamp" +%s
    return
  fi
  # BSD date lacks -d and fractional-second parsing; normalize it as a
  # read-only fallback so operator workstations can run this observer too.
  if [[ "$timestamp" =~ ^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$ ]]; then
    base="${BASH_REMATCH[1]}"
    zone="${BASH_REMATCH[3]}"
    zone="${zone/Z/+0000}"
    zone="${zone/:/}"
    date -j -f '%Y-%m-%dT%H:%M:%S%z' "${base}${zone}" +%s
    return
  fi
  return 1
}

timestamp_audio_freshness_seconds() {
  local timestamp epoch now age newest=""
  now=$(date +%s)
  while IFS= read -r timestamp; do
    epoch=$(epoch_from_timestamp "$timestamp") || continue
    age=$((now - epoch))
    if (( age >= 0 )) && { [[ -z "$newest" ]] || (( age < newest )); }; then
      newest="$age"
    fi
  done
  [[ -n "$newest" ]] || return 1
  printf '%s\n' "$newest"
}

sources_are_healthy() {
  jq -e '.sources | type == "array" and length != 0 and all(.[]; .state == "HEALTHY")'
}

audio_freshness_seconds() {
  local health_json numeric timestamps dispatched
  health_json=$(cat)
  numeric=$(printf '%s' "$health_json" | numeric_audio_freshness_seconds) || numeric=""
  timestamps=$(printf '%s' "$health_json" | audio_dispatch_timestamps)
  dispatched=$(printf '%s\n' "$timestamps" | timestamp_audio_freshness_seconds) || dispatched=""
  if [[ -n "$numeric" && -n "$dispatched" ]]; then
    awk -v numeric="$numeric" -v dispatched="$dispatched" 'BEGIN { print (numeric < dispatched ? numeric : dispatched) }'
  elif [[ -n "$numeric" ]]; then
    printf '%s\n' "$numeric"
  elif [[ -n "$dispatched" ]]; then
    printf '%s\n' "$dispatched"
  else
    return 1
  fi
}

require_command curl || true
require_command jq || true
require_command docker || true
require_command df || true

authenticated_health() {
  local login callback headers session_header cookie mode owner current_uid callback_pattern cookie_pattern max_age_pattern
  callback_pattern='^/api/v2/auth/callback\?[A-Za-z0-9._~%=&/-]+$'
  cookie_pattern='^_gothic_session=[A-Za-z0-9._~%+=:@|/-]+$'
  max_age_pattern='(^|;[[:space:]]*)[Mm]ax-[Aa]ge[[:space:]]*=[[:space:]]*(-?[0-9]+)'
  [[ -r "$AUTH_FILE" ]] || return 1
  mode=$(stat -c '%a' "$AUTH_FILE" 2>/dev/null || stat -f '%Lp' "$AUTH_FILE" 2>/dev/null) || return 1
  [[ "$mode" == "600" ]] || return 1
  owner=$(stat -c '%u' "$AUTH_FILE" 2>/dev/null || stat -f '%u' "$AUTH_FILE" 2>/dev/null) || return 1
  current_uid=$(id -u) || return 1
  [[ "$owner" == "$current_uid" || "$owner" == "0" ]] || return 1
  # Read the credential JSON as jq input so neither value enters a process argv.
  login=$(jq -ce '
    def credential: type == "string" and length != 0 and (test("[\\r\\n]") | not);
    if (.clientid | credential) and (.password | credential)
    then {username: .clientid, password: .password, redirectUrl: "/"}
    else error("invalid watchdog credentials") end
  ' "$AUTH_FILE") || return 1
  callback=$(printf '%s' "$login" | curl --fail --silent --show-error --connect-timeout 3 --max-time 8 --max-filesize "$AUTH_LOGIN_MAX_BYTES" --header 'Content-Type: application/json' --data-binary @- "http://${BIRDNET_BIND_IP}:${WEB_PORT}/api/v2/auth/login" | jq -er 'select(.success == true) | .redirectUrl | strings') || return 1
  [[ ${#callback} -le "$AUTH_CALLBACK_MAX_BYTES" ]] || return 1
  [[ "$callback" =~ $callback_pattern ]] || return 1
  # The strict callback grammar keeps this stdin-only curl config free of quotes,
  # escapes, CRLF, and alternate origins.
  headers=$(printf 'url = "http://%s:%s%s"\n' "$BIRDNET_BIND_IP" "$WEB_PORT" "$callback" | curl --config - --fail --silent --show-error --connect-timeout 3 --max-time 8 --max-filesize "$AUTH_CALLBACK_HEADER_MAX_BYTES" -D - -o /dev/null) || return 1
  [[ ${#headers} -le "$AUTH_CALLBACK_HEADER_MAX_BYTES" ]] || return 1
  # Browser cookie processing is ordered: a later Set-Cookie for the same name
  # replaces an earlier one. Keep the last session header and reject its expiry.
  session_header=$(printf '%s\n' "$headers" | awk 'tolower($0) ~ /^set-cookie:[[:space:]]*_gothic_session=/ { header = $0 } END { print header }')
  [[ -n "$session_header" ]] || return 1
  if [[ "$session_header" =~ $max_age_pattern ]] && (( ${BASH_REMATCH[2]} <= 0 )); then
    return 1
  fi
  cookie=$(printf '%s\n' "$session_header" | awk '{ sub(/^[^:]*:[[:space:]]*/, ""); sub(/;.*/, ""); print }')
  [[ ${#cookie} -le 4096 && "$cookie" =~ $cookie_pattern ]] || return 1
  # curl --config sends /api/v2/health/audio without exposing the session
  # credential in argv or persistent jars.
  printf 'header = "Cookie: %s"\nurl = "%s"\n' "$cookie" "$HEALTH_URL" | curl --config - --fail --silent --show-error --connect-timeout 3 --max-time 8 --max-filesize "$AUTH_HEALTH_MAX_BYTES"
}

if [[ -n "$AUTH_FILE" ]]; then
  health_json=$(authenticated_health) || health_json=""
else
  health_json=$(curl --fail --silent --show-error --connect-timeout 3 --max-time 8 --max-filesize "$AUTH_HEALTH_MAX_BYTES" "$HEALTH_URL") || health_json=""
fi
if [[ -n "$health_json" ]]; then # /api/v2/health/audio
  if printf '%s' "$health_json" | sources_are_healthy >/dev/null; then
    report "INFO: every reported audio source is HEALTHY"
  else
    fail "audio health response has no HEALTHY source set"
  fi
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
