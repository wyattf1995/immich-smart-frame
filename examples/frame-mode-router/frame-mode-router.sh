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
    photos|home|weather|cameras|calendar) return 0 ;;
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
frameos_enabled=0
if [ -n "$FRAMEOS_PACKAGE" ] || [ -n "$FRAMEOS_ACTIVITY" ] || [ -n "$FRAMEOS_RECEIVER" ]; then
  [ -n "$FRAMEOS_PACKAGE" ] && [ -n "$FRAMEOS_ACTIVITY" ] && [ -n "$FRAMEOS_RECEIVER" ] || \
    fail 'FRAMEOS_PACKAGE, FRAMEOS_ACTIVITY, and FRAMEOS_RECEIVER must be configured together'
  frameos_enabled=1
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

fully_package="${FULLY_ACTIVITY%%/*}"

saved_mode() {
  mode=''
  if [ -r "$STATE_FILE" ]; then
    IFS= read -r mode < "$STATE_FILE"
  fi
  if is_mode "$mode" && { [ "$frameos_enabled" = 1 ] || [ "$mode" != weather ]; }; then
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
      weather) printf '%s\n' cameras ;;
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
      cameras) printf '%s\n' weather ;;
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
  sleep 1
  # This best-effort content scroll collapses Firefox's toolbar on the tested
  # fixed Home Assistant layouts. It is not a permanent fullscreen guarantee.
  input swipe 100 1000 100 500 100 >/dev/null 2>&1 || true
}

open_frameos_mode() {
  case "$1" in
    photos) frameos_mode=PHOTOS ;;
    home) frameos_mode=HOME ;;
    weather) frameos_mode=WEATHER ;;
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

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  printf 'frame-mode-router: another transition is already running\n' >&2
  exit 75
fi
cleanup_lock() {
  rmdir "$LOCK_DIR" 2>/dev/null || true
}
trap cleanup_lock EXIT HUP INT TERM

current="$(current_mode)"
case "$action" in
  next) destination="$(next_mode "$current")" ;;
  prev|previous) destination="$(previous_mode "$current")" ;;
  show) destination="$requested_mode" ;;
esac

show_mode "$destination"
