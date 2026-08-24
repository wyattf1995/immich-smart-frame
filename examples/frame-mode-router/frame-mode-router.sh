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
    photos|home|cameras|calendar) return 0 ;;
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

fully_package="${FULLY_ACTIVITY%%/*}"

saved_mode() {
  mode=''
  if [ -r "$STATE_FILE" ]; then
    IFS= read -r mode < "$STATE_FILE"
  fi
  if is_mode "$mode"; then
    printf '%s\n' "$mode"
  else
    printf '%s\n' home
  fi
}

current_mode() {
  resumed="$(dumpsys activity activities 2>/dev/null | grep 'mResumedActivity' | head -n 1)"
  case "$resumed" in
    *"$fully_package"*) printf '%s\n' photos ;;
    *"$FIREFOX_PACKAGE"*) saved_mode ;;
    *) saved_mode ;;
  esac
}

next_mode() {
  case "$1" in
    photos) printf '%s\n' home ;;
    home) printf '%s\n' cameras ;;
    cameras) printf '%s\n' calendar ;;
    calendar) printf '%s\n' photos ;;
  esac
}

previous_mode() {
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

show_mode() {
  case "$1" in
    photos)
      am start --activity-reorder-to-front \
        -n "$FULLY_ACTIVITY" >/dev/null 2>&1 || return 1
      ;;
    home) open_firefox_mode "$HOME_URL" || return 1 ;;
    cameras) open_firefox_mode "$CAMERAS_URL" || return 1 ;;
    calendar) open_firefox_mode "$CALENDAR_URL" || return 1 ;;
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
