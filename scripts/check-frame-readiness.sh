#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage: check-frame-readiness.sh [--compose-file FILE] [--adb SERIAL]
                                [--router-config FILE] [--ha-check-command COMMAND]
                                [--array-check-command COMMAND]

Runs one read-only readiness sample. It never restarts Docker, Home Assistant,
or Android services. Optional commands are locally trusted operator hooks.
EOF
  exit 2
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/docker-compose.yaml"
adb_serial=""
router_config=""
ha_check_command=""
array_check_command=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose-file) compose_file="${2:-}"; shift 2 ;;
    --adb) adb_serial="${2:-}"; shift 2 ;;
    --router-config) router_config="${2:-}"; shift 2 ;;
    --ha-check-command) ha_check_command="${2:-}"; shift 2 ;;
    --array-check-command) array_check_command="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) usage ;;
  esac
done

[[ -f "$compose_file" ]] || { printf 'readiness: missing Compose file: %s\n' "$compose_file" >&2; exit 2; }

failed=0
check() {
  local label="$1"
  shift
  if "$@"; then
    printf 'ready: %s\n' "$label"
  else
    printf 'not ready: %s\n' "$label" >&2
    failed=1
  fi
}

informational_check() {
  local label="$1"
  local unavailable_message="$2"
  shift 2
  if "$@"; then
    printf 'ready (optional): %s\n' "$label"
  else
    printf 'informational: %s\n' "$unavailable_message"
  fi
}

frameos_home_owner() {
  local resolved_home home_component role_state
  resolved_home="$(adb -s "$adb_serial" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME)" || return 1
  home_component="$(printf '%s\n' "$resolved_home" | tr -d '\r' | awk 'NF { component = $0 } END { print component }')"
  [[ "$home_component" == 'com.wyattfleming.frameos/.MainActivity' ]] && return 0

  # Lenovo's stock splash declares a higher resolver priority even when Android's
  # authoritative HOME role belongs to FrameOS. Fall back to exact role ownership.
  role_state="$(adb -s "$adb_serial" shell dumpsys role)" || return 1
  printf '%s\n' "${role_state//$'\r'/}" | awk '
    /^[[:space:]]*name=android\.app\.role\.HOME[[:space:]]*$/ {
      home_records += 1
      in_home = 1
      next
    }
    /^[[:space:]]*name=/ { in_home = 0 }
    in_home && /^[[:space:]]*holders=/ {
      holder_records += 1
      holder = $0
      sub(/^[[:space:]]*holders=[[:space:]]*/, "", holder)
      sub(/[[:space:]]+$/, "", holder)
      if (holder == "com.wyattfleming.frameos") found = 1
    }
    END { exit !(home_records == 1 && holder_records == 1 && found == 1) }
  '
}

frameos_resumed() {
  adb -s "$adb_serial" shell dumpsys activity activities | \
    grep -F 'mResumedActivity' | grep -Fq 'com.wyattfleming.frameos/.MainActivity'
}

frame_input_accessibility_ready() {
  local accessibility_state enabled_services bound_services
  accessibility_state="$(adb -s "$adb_serial" shell dumpsys accessibility)" || return 1
  accessibility_state="${accessibility_state//$'\r'/}"

  # A UI-test automation service suppresses ordinary accessibility services by
  # default. Never leave one registered on the production frame after E2E.
  if grep -Fq 'Ui Automation[' <<< "$accessibility_state"; then
    return 1
  fi

  enabled_services="$(grep -F 'Enabled services:' <<< "$accessibility_state" | head -n 1)"
  if [[ "$enabled_services" != *'io.github.sds100.keymapper/io.github.sds100.keymapper.system.accessibility.MyAccessibilityService'* ]]; then
    return 0
  fi

  bound_services="$(grep -F 'Bound services:' <<< "$accessibility_state" | head -n 1)"
  [[ "$bound_services" == *'Service[label=Key Mapper'* ]]
}

frameos_overlay_ready() {
  local app_ops
  app_ops="$(adb -s "$adb_serial" shell appops get com.wyattfleming.frameos SYSTEM_ALERT_WINDOW)" || return 1
  printf '%s\n' "${app_ops//$'\r'/}" | awk '
    /^[[:space:]]*SYSTEM_ALERT_WINDOW:[[:space:]]*/ {
      records += 1
      if ($0 ~ /^[[:space:]]*SYSTEM_ALERT_WINDOW:[[:space:]]*allow([;[:space:]]|$)/) {
        allowed += 1
      } else {
        invalid += 1
      }
    }
    END { exit !(records == 1 && allowed == 1 && invalid == 0) }
  '
}

frameos_duraspeed_ready() {
  local raw_status status config
  raw_status="$(adb -s "$adb_serial" shell dumpsys duraspeed status)" || return 1
  status="$(printf '%s\n' "${raw_status//$'\r'/}" | awk '
    NF {
      records += 1
      value = $0
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
    }
    END { if (records == 1) print value }
  ')"

  # With DuraSpeed disabled globally there is no per-app suppression to exempt.
  [[ "$status" == false ]] && return 0
  [[ "$status" == true ]] || return 1

  config="$(adb -s "$adb_serial" shell dumpsys duraspeed config)" || return 1
  printf '%s\n' "${config//$'\r'/}" | awk '
    /^[[:space:]]*(PlatformWhitelist|AppWhitelist):[[:space:]]*/ {
      records += 1
      if ($0 ~ /^[[:space:]]*PlatformWhitelist:/) {
        platform_records += 1
      } else {
        app_records += 1
      }
      list = $0
      sub(/^[[:space:]]*(PlatformWhitelist|AppWhitelist):[[:space:]]*/, "", list)
      sub(/[[:space:]]+$/, "", list)
      if (list !~ /^\[.*\]$/) {
        invalid += 1
        next
      }
      sub(/^\[/, "", list)
      sub(/\]$/, "", list)
      count = split(list, entries, ",")
      for (item_index = 1; item_index <= count; item_index += 1) {
        entry = entries[item_index]
        sub(/^[[:space:]]+/, "", entry)
        sub(/[[:space:]]+$/, "", entry)
        if (entry == "com.wyattfleming.frameos") found = 1
      }
    }
    END {
      safe = records >= 1 && platform_records <= 1 && app_records <= 1 && found == 1 && invalid == 0
      exit !safe
    }
  '
}

compose=(docker compose -f "$compose_file")
check 'Kiosk liveness (/livez)' "${compose[@]}" exec -T immich-kiosk /kiosk --livecheck
check 'Kiosk dependency readiness (/readyz)' "${compose[@]}" exec -T immich-kiosk /kiosk --readycheck

if [[ -n "$array_check_command" ]]; then
  check 'Unraid array operator hook' sh -c "$array_check_command"
else
  printf 'not checked: Unraid array (pass --array-check-command)\n'
fi
if [[ -n "$ha_check_command" ]]; then
  check 'Home Assistant operator hook' sh -c "$ha_check_command"
else
  printf 'not checked: Home Assistant (pass --ha-check-command)\n'
fi
if [[ -n "$adb_serial" ]]; then
  check 'Frame ADB transport' adb -s "$adb_serial" get-state
  check 'FrameOS package' adb -s "$adb_serial" shell pidof com.wyattfleming.frameos
  check 'FrameOS display-over-other-apps permission' frameos_overlay_ready
  check 'FrameOS DuraSpeed policy' frameos_duraspeed_ready
  check 'FrameOS HOME ownership' frameos_home_owner
  check 'FrameOS resumed activity' frameos_resumed
  check 'Frame input accessibility' frame_input_accessibility_ready
  informational_check 'Key Mapper sysbridge' 'Key Mapper sysbridge is not running (FrameOS direct keys remain available)' \
    adb -s "$adb_serial" shell pidof keymapper_sysbridge
  if [[ -n "$router_config" ]]; then
    check 'Frame router state' adb -s "$adb_serial" shell sh /data/local/tmp/frame-mode-router.sh status "$router_config"
  fi
else
  printf 'not checked: frame transport/router (pass --adb and optionally --router-config)\n'
fi

if [[ "$failed" -ne 0 ]]; then
  printf 'readiness sample failed; inspect the reported dependency. This tool intentionally did not restart anything.\n' >&2
  exit 1
fi
printf 'readiness sample passed\n'
