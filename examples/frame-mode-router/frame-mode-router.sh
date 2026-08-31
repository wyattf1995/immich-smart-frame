#!/system/bin/sh

set -u

DEFAULT_CONFIG=/data/local/tmp/frame-mode-router.conf

fail() {
  printf 'frame-mode-router: %s\n' "$*" >&2
  exit 2
}

usage() {
  fail 'usage: frame-mode-router.sh {next|prev|status|show MODE} [CONFIG]'
}

is_mode() {
  case "$1" in
    photos|home|weather|birds|cameras|calendar) return 0 ;;
    *) return 1 ;;
  esac
}

is_http_url() {
  case "$1" in
    http://*|https://*) return 0 ;;
    *) return 1 ;;
  esac
}

action="${1:-}"
case "$action" in
  show)
    [ "$#" -ge 2 ] && [ "$#" -le 3 ] || usage
    requested_mode="$2"
    config_file="${3:-$DEFAULT_CONFIG}"
    is_mode "$requested_mode" || fail "unknown mode: $requested_mode"
    ;;
  next|prev|previous|status)
    [ "$#" -le 2 ] || usage
    config_file="${2:-$DEFAULT_CONFIG}"
    ;;
  *) usage ;;
esac

[ -r "$config_file" ] || fail "cannot read config: $config_file"
# shellcheck disable=SC1090
. "$config_file"

: "${STATE_FILE:?STATE_FILE is required}"
: "${LOCK_DIR:?LOCK_DIR is required}"
: "${HOME_URL:?HOME_URL is required}"
: "${CAMERAS_URL:?CAMERAS_URL is required}"
: "${CALENDAR_URL:?CALENDAR_URL is required}"
: "${FULLY_ACTIVITY:?FULLY_ACTIVITY is required}"
: "${FIREFOX_PACKAGE:?FIREFOX_PACKAGE is required}"

FRAMEOS_PACKAGE="${FRAMEOS_PACKAGE:-}"
FRAMEOS_ACTIVITY="${FRAMEOS_ACTIVITY:-}"
FRAMEOS_RECEIVER="${FRAMEOS_RECEIVER:-}"
FRAMEOS_CONTROL_ACTION="${FRAMEOS_CONTROL_ACTION:-com.wyattfleming.frameos.CONTROL}"
FRAMEOS_BIRDS_ENABLED="${FRAMEOS_BIRDS_ENABLED:-0}"
FIREFOX_READY_MAX_PROBES="${FIREFOX_READY_MAX_PROBES:-4}"
FIREFOX_READY_POLL_SECONDS="${FIREFOX_READY_POLL_SECONDS:-1}"
LOCK_LEASE_SECONDS="${LOCK_LEASE_SECONDS:-90}"
LOCK_PROC_ROOT="${FRAME_ROUTER_PROC_ROOT:-/proc}"
LOCK_TEST_PROCESS_START_TOKEN="${FRAME_ROUTER_PROCESS_START_TOKEN:-}"
frameos_enabled=0
if [ -n "$FRAMEOS_PACKAGE" ] || [ -n "$FRAMEOS_ACTIVITY" ] || [ -n "$FRAMEOS_RECEIVER" ]; then
  [ -n "$FRAMEOS_PACKAGE" ] && [ -n "$FRAMEOS_ACTIVITY" ] && [ -n "$FRAMEOS_RECEIVER" ] || \
    fail 'FRAMEOS_PACKAGE, FRAMEOS_ACTIVITY, and FRAMEOS_RECEIVER must be configured together'
  frameos_enabled=1
fi

case "$FRAMEOS_BIRDS_ENABLED" in
  0|1) ;;
  *) fail 'FRAMEOS_BIRDS_ENABLED must be 0 or 1' ;;
esac
[ "$FRAMEOS_BIRDS_ENABLED" = 0 ] || [ "$frameos_enabled" = 1 ] || \
  fail 'FRAMEOS_BIRDS_ENABLED requires FrameOS'
if [ "$action" = show ] && [ "$requested_mode" = birds ] && [ "$FRAMEOS_BIRDS_ENABLED" != 1 ]; then
  fail 'Birds mode is not enabled'
fi

case "$STATE_FILE:$LOCK_DIR" in
  /*:/*) ;;
  *) fail 'STATE_FILE and LOCK_DIR must be absolute paths' ;;
esac

for configured_url in "$HOME_URL" "$CAMERAS_URL" "$CALENDAR_URL"; do
  is_http_url "$configured_url" || fail 'dashboard URLs must use http or https'
done

case "$FULLY_ACTIVITY" in
  */*) ;;
  *) fail 'FULLY_ACTIVITY must be an Android package/activity component' ;;
esac
case "$FULLY_ACTIVITY" in
  *[!A-Za-z0-9._/]*) fail 'FULLY_ACTIVITY contains unsupported characters' ;;
esac
case "$FIREFOX_PACKAGE" in
  *[!A-Za-z0-9._]*|'') fail 'FIREFOX_PACKAGE contains unsupported characters' ;;
esac
case "$FRAMEOS_PACKAGE" in
  *[!A-Za-z0-9._]*) fail 'FRAMEOS_PACKAGE contains unsupported characters' ;;
esac
case "$FRAMEOS_RECEIVER" in
  '') ;;
  */*) ;;
  *) fail 'FRAMEOS_RECEIVER must be an Android package/receiver component' ;;
esac
case "$FRAMEOS_ACTIVITY" in
  '') ;;
  */*) ;;
  *) fail 'FRAMEOS_ACTIVITY must be an Android package/activity component' ;;
esac
case "$FRAMEOS_ACTIVITY" in
  *[!A-Za-z0-9._/]*) fail 'FRAMEOS_ACTIVITY contains unsupported characters' ;;
esac
case "$FRAMEOS_RECEIVER" in
  *[!A-Za-z0-9._/]*) fail 'FRAMEOS_RECEIVER contains unsupported characters' ;;
esac
case "$FRAMEOS_CONTROL_ACTION" in
  *[!A-Za-z0-9._]*|'') fail 'FRAMEOS_CONTROL_ACTION contains unsupported characters' ;;
esac
case "$FIREFOX_READY_MAX_PROBES" in
  *[!0-9]*|'') fail 'FIREFOX_READY_MAX_PROBES must be a positive integer' ;;
esac
[ "$FIREFOX_READY_MAX_PROBES" -ge 1 ] || fail 'FIREFOX_READY_MAX_PROBES must be a positive integer'
case "$FIREFOX_READY_POLL_SECONDS" in
  *[!0-9]*|'') fail 'FIREFOX_READY_POLL_SECONDS must be a positive integer' ;;
esac
[ "$FIREFOX_READY_POLL_SECONDS" -ge 1 ] || fail 'FIREFOX_READY_POLL_SECONDS must be a positive integer'
case "$LOCK_LEASE_SECONDS" in
  *[!0-9]*|'') fail 'LOCK_LEASE_SECONDS must be a positive integer' ;;
esac
[ "$LOCK_LEASE_SECONDS" -ge 1 ] || fail 'LOCK_LEASE_SECONDS must be a positive integer'

fully_package="${FULLY_ACTIVITY%%/*}"

saved_mode() {
  mode=''
  if [ -r "$STATE_FILE" ]; then
    IFS= read -r mode < "$STATE_FILE"
  fi
  if is_mode "$mode" && \
      { [ "$frameos_enabled" = 1 ] || { [ "$mode" != weather ] && [ "$mode" != birds ]; }; } && \
      { [ "$mode" != birds ] || [ "$FRAMEOS_BIRDS_ENABLED" = 1 ]; }; then
    printf '%s\n' "$mode"
  else
    printf '%s\n' home
  fi
}

current_mode() {
  resumed="$(dumpsys activity activities 2>/dev/null | grep 'mResumedActivity' | head -n 1)"
  if [ "$frameos_enabled" = 1 ]; then
    case "$resumed" in
      *"$FRAMEOS_PACKAGE"*) saved_mode; return ;;
    esac
  fi
  case "$resumed" in
    *"$fully_package"*) printf '%s\n' photos ;;
    *"$FIREFOX_PACKAGE"*) saved_mode ;;
    *) saved_mode ;;
  esac
}

next_mode() {
  if [ "$frameos_enabled" = 1 ]; then
    case "$1" in
      photos) printf '%s\n' home ;;
      home) printf '%s\n' weather ;;
      weather)
        if [ "$FRAMEOS_BIRDS_ENABLED" = 1 ]; then
          printf '%s\n' birds
        else
          printf '%s\n' cameras
        fi
        ;;
      birds) printf '%s\n' cameras ;;
      cameras) printf '%s\n' calendar ;;
      calendar) printf '%s\n' photos ;;
    esac
    return
  fi
  case "$1" in
    photos) printf '%s\n' home ;;
    home) printf '%s\n' cameras ;;
    cameras) printf '%s\n' calendar ;;
    calendar) printf '%s\n' photos ;;
  esac
}

previous_mode() {
  if [ "$frameos_enabled" = 1 ]; then
    case "$1" in
      photos) printf '%s\n' calendar ;;
      home) printf '%s\n' photos ;;
      weather) printf '%s\n' home ;;
      birds) printf '%s\n' weather ;;
      cameras)
        if [ "$FRAMEOS_BIRDS_ENABLED" = 1 ]; then
          printf '%s\n' birds
        else
          printf '%s\n' weather
        fi
        ;;
      calendar) printf '%s\n' cameras ;;
    esac
    return
  fi
  case "$1" in
    photos) printf '%s\n' calendar ;;
    home) printf '%s\n' photos ;;
    cameras) printf '%s\n' home ;;
    calendar) printf '%s\n' cameras ;;
  esac
}

save_mode() {
  temporary_state="${STATE_FILE}.$$"
  (umask 077 && printf '%s\n' "$1" > "$temporary_state") || return 1
  mv "$temporary_state" "$STATE_FILE"
}

open_firefox_mode() {
  destination_url="$1"
  am start --activity-reorder-to-front \
    -a android.intent.action.VIEW \
    -d "$destination_url" \
    -p "$FIREFOX_PACKAGE" >/dev/null 2>&1 || return 1
  if ! wait_for_firefox_ready; then
    # The activity may still finish its launch later. Do not swipe whatever
    # unrelated surface happens to remain resumed at this bounded deadline.
    return 0
  fi
  # This best-effort content scroll collapses Firefox's toolbar on the tested
  # fixed Home Assistant layouts. It is not a permanent fullscreen guarantee.
  input swipe 100 1000 100 500 100 >/dev/null 2>&1 || true
}

firefox_is_resumed() {
  dumpsys activity activities 2>/dev/null | grep 'mResumedActivity' | grep -Fq "$FIREFOX_PACKAGE"
}

wait_for_firefox_ready() {
  probe=0
  while [ "$probe" -lt "$FIREFOX_READY_MAX_PROBES" ]; do
    if firefox_is_resumed; then
      return 0
    fi
    probe=$((probe + 1))
    [ "$probe" -lt "$FIREFOX_READY_MAX_PROBES" ] || break
    sleep "$FIREFOX_READY_POLL_SECONDS"
  done
  return 1
}

open_frameos_mode() {
  case "$1" in
    photos) frameos_mode=PHOTOS ;;
    home) frameos_mode=HOME ;;
    weather) frameos_mode=WEATHER ;;
    birds) frameos_mode=BIRDS ;;
    cameras) frameos_mode=CAMERAS ;;
    calendar) frameos_mode=CALENDAR ;;
    *) return 2 ;;
  esac

  # Fully's separate priority process can otherwise reclaim the foreground.
  # Only pay Android's force-stop cost while that process is actually alive;
  # subsequent FrameOS-to-FrameOS transitions stay on the warm path.
  if pidof "$fully_package" >/dev/null 2>&1; then
    am force-stop "$fully_package" >/dev/null 2>&1 || true
  fi
  # FrameOS embeds its own Gecko runtime. Retire the legacy Firefox task once
  # mode routing leaves it so its tabs and media processes do not consume the
  # frame's limited memory in the background.
  if pidof "$FIREFOX_PACKAGE" >/dev/null 2>&1; then
    am force-stop "$FIREFOX_PACKAGE" >/dev/null 2>&1 || true
  fi
  am broadcast --user 0 \
    -a "$FRAMEOS_CONTROL_ACTION" \
    -n "$FRAMEOS_RECEIVER" \
    --es frameos.mode "$frameos_mode" >/dev/null 2>&1 || return 1
  am start --activity-reorder-to-front \
    -n "$FRAMEOS_ACTIVITY" >/dev/null 2>&1
}

show_mode() {
  if [ "$frameos_enabled" = 1 ]; then
    open_frameos_mode "$1" || return 1
    save_mode "$1"
    return
  fi
  case "$1" in
    photos)
      am start --activity-reorder-to-front \
        -n "$FULLY_ACTIVITY" >/dev/null 2>&1 || return 1
      ;;
    home) open_firefox_mode "$HOME_URL" || return 1 ;;
    cameras) open_firefox_mode "$CAMERAS_URL" || return 1 ;;
    calendar) open_firefox_mode "$CALENDAR_URL" || return 1 ;;
    weather) return 2 ;;
    *) return 2 ;;
  esac
  save_mode "$1"
}

if [ "$action" = status ]; then
  current_mode
  exit 0
fi

read_lock_owner() {
  [ -L "$LOCK_DIR" ] || return 1
  lock_record="$(readlink "$LOCK_DIR" 2>/dev/null)" || return 1
  IFS=' ' read -r lock_pid lock_process_start lock_started lock_extra <<EOF
$lock_record
EOF
  case "$lock_pid:$lock_process_start:$lock_started:${lock_extra:-}" in
    *[!0-9:]*|:*|*::?*) return 1 ;;
  esac
  [ -z "${lock_extra:-}" ] || return 1
  printf '%s %s %s\n' "$lock_pid" "$lock_process_start" "$lock_started"
}

process_start_token() {
  # The override is only for the portable shell contract test, whose host may
  # not expose Android/Linux /proc. Real deployments leave it unset.
  if [ "$LOCK_PROC_ROOT" != /proc ] && [ -n "$LOCK_TEST_PROCESS_START_TOKEN" ]; then
    case "$LOCK_TEST_PROCESS_START_TOKEN" in
      *[!0-9]*|'') return 1 ;;
    esac
    printf '%s\n' "$LOCK_TEST_PROCESS_START_TOKEN"
    return 0
  fi
  process_stat="$LOCK_PROC_ROOT/$1/stat"
  [ -r "$process_stat" ] || return 1
  # /proc/<pid>/stat field 2 is parenthesized and may contain spaces; strip it
  # before selecting the original field 22 (the 20th remaining field).
  process_start="$(awk '{sub(/^.*\) /, ""); print $20}' "$process_stat")" || return 1
  case "$process_start" in
    *[!0-9]*|'') return 1 ;;
  esac
  printf '%s\n' "$process_start"
}

create_lock() {
  lock_started="$(date +%s)" || return 1
  case "$lock_started" in
    *[!0-9]*|'') return 1 ;;
  esac
  lock_process_start="$(process_start_token "$$")" || return 1
  lock_owner="$$ $lock_process_start $lock_started"
  if ln -s "$lock_owner" "$LOCK_DIR" 2>/dev/null; then
    return 0
  fi
  return 1
}

recover_stale_lock() {
  lock_record="$(read_lock_owner)" || {
    printf 'frame-mode-router: lock record is malformed; manual recovery is required\n' >&2
    return 1
  }
  lock_pid="${lock_record%% *}"
  lock_record="${lock_record#* }"
  lock_process_start="${lock_record%% *}"
  lock_started="${lock_record#* }"

  # Never reclaim a lock whose recorded owner still exists, even when a slow
  # command has exceeded its lease. This deliberately prefers availability of
  # the in-flight transition over a competing gesture.
  if kill -0 "$lock_pid" 2>/dev/null; then
    current_process_start="$(process_start_token "$lock_pid")" || {
      printf 'frame-mode-router: another transition is already running (live owner identity cannot be verified)\n' >&2
      return 1
    }
    if [ "$current_process_start" = "$lock_process_start" ]; then
      printf 'frame-mode-router: another transition is already running (lock held by live owner %s)\n' "$lock_pid" >&2
      return 1
    fi
  fi

  lock_now="$(date +%s)" || return 1
  case "$lock_now" in
    *[!0-9]*|'') return 1 ;;
  esac
  if [ "$lock_now" -lt "$lock_started" ] || [ $((lock_now - lock_started)) -lt "$LOCK_LEASE_SECONDS" ]; then
    printf 'frame-mode-router: another transition is already running (owner is gone but lease has not expired)\n' >&2
    return 1
  fi

  stale_lock="${LOCK_DIR}.stale.$$"
  mv "$LOCK_DIR" "$stale_lock" 2>/dev/null || return 1
  rm -f "$stale_lock" || return 1
  return 0
}

if ! create_lock; then
  recover_stale_lock || exit 75
  create_lock || {
    printf 'frame-mode-router: another transition is already running\n' >&2
    exit 75
  }
fi
cleanup_lock() {
  if [ -L "$LOCK_DIR" ] && [ "$(readlink "$LOCK_DIR" 2>/dev/null)" = "$lock_owner" ]; then
    rm -f "$LOCK_DIR" 2>/dev/null || true
  fi
}
trap cleanup_lock EXIT HUP INT TERM

case "$action" in
  next)
    current="$(current_mode)"
    destination="$(next_mode "$current")"
    ;;
  prev|previous)
    current="$(current_mode)"
    destination="$(previous_mode "$current")"
    ;;
  show) destination="$requested_mode" ;;
esac

show_mode "$destination"
