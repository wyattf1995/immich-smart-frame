#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
router="$repo_root/examples/frame-mode-router/frame-mode-router.sh"
router_shell="${FRAME_ROUTER_SHELL:-sh}"

fail() {
  printf 'frame-mode-router contract failed: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local description="$3"
  [[ "$actual" == "$expected" ]] || fail "$description (expected '$expected', got '$actual')"
}

assert_file_contains() {
  local needle="$1"
  local file="$2"
  local description="$3"
  grep -Fq -- "$needle" "$file" || fail "$description (missing '$needle')"
}

assert_file_not_contains() {
  local needle="$1"
  local file="$2"
  local description="$3"
  ! grep -Fq -- "$needle" "$file" || fail "$description (found '$needle')"
}

assert_file_count() {
  local needle="$1"
  local file="$2"
  local expected="$3"
  local description="$4"
  local actual
  actual="$(grep -Fxc -- "$needle" "$file" || true)"
  [[ "$actual" == "$expected" ]] || fail "$description (expected $expected, got $actual)"
}

[[ -f "$router" ]] || fail "missing $router"
[[ -x "$router" ]] || fail "$router must be executable"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/frame-mode-router-test.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
fake_bin="$tmp_dir/bin"
proc_root="$tmp_dir/proc"
mkdir -p "$fake_bin"
mkdir -p "$proc_root/$$"

write_proc_start() {
  local pid="$1"
  local start_token="$2"
  mkdir -p "$proc_root/$pid"
  {
    printf '%s (sh)' "$pid"
    for _ in $(seq 1 19); do printf ' 0'; done
    printf ' %s\n' "$start_token"
  } > "$proc_root/$pid/stat"
}

write_proc_start "$$" 111
export FRAME_ROUTER_PROCESS_START_TOKEN=111

cat > "$fake_bin/dumpsys" <<'EOF'
#!/usr/bin/env sh
if [ "${1:-}" != "activity" ] || [ "${2:-}" != "activities" ]; then
  exit 2
fi
printf 'dumpsys\n' >> "$FRAME_ROUTER_LOG"
if [ -n "${FRAME_ROUTER_FIREFOX_READY_AFTER:-}" ]; then
  calls_file="${FRAME_ROUTER_DUMPSYS_CALLS:?}"
  calls=0
  if [ -r "$calls_file" ]; then
    calls="$(cat "$calls_file")"
  fi
  calls=$((calls + 1))
  printf '%s\n' "$calls" > "$calls_file"
  if [ "$calls" -gt "$FRAME_ROUTER_FIREFOX_READY_AFTER" ]; then
    printf '%s\n' '  mResumedActivity: ActivityRecord{42 u0 org.mozilla.firefox/org.mozilla.gecko.BrowserApp}'
  else
    printf '%s\n' '  mResumedActivity: ActivityRecord{42 u0 com.example.launcher/.MainActivity}'
  fi
  exit 0
fi
if [ "${FRAME_ROUTER_FOREGROUND:-}" = background-firefox ]; then
  printf '%s\n' '  mResumedActivity: ActivityRecord{42 u0 com.example.launcher/.MainActivity}'
  printf '%s\n' '  mLastPausedActivity: ActivityRecord{42 u0 org.mozilla.firefox/org.mozilla.gecko.BrowserApp}'
  exit 0
fi
if [ -n "${FRAME_ROUTER_FIREFOX_STARTED:-}" ] && [ -f "$FRAME_ROUTER_FIREFOX_STARTED" ]; then
  printf '%s\n' '  mResumedActivity: ActivityRecord{42 u0 org.mozilla.firefox/org.mozilla.gecko.BrowserApp}'
  exit 0
fi
case "${FRAME_ROUTER_FOREGROUND:-firefox}" in
  fully)
    printf '%s\n' '  mResumedActivity: ActivityRecord{42 u0 de.ozerov.fully/.FullyActivity}'
    ;;
  firefox)
    printf '%s\n' '  mResumedActivity: ActivityRecord{42 u0 org.mozilla.firefox/org.mozilla.gecko.BrowserApp}'
    ;;
  frameos)
    printf '%s\n' '  mResumedActivity: ActivityRecord{42 u0 com.wyattfleming.frameos/.MainActivity}'
    ;;
  unknown)
    printf '%s\n' '  mResumedActivity: ActivityRecord{42 u0 com.example.unknown/.MainActivity}'
    ;;
  *)
    printf '%s\n' "$FRAME_ROUTER_FOREGROUND"
    ;;
esac
EOF

cat > "$fake_bin/am" <<'EOF'
#!/usr/bin/env sh
printf 'am %s\n' "$*" >> "$FRAME_ROUTER_LOG"
case " $* " in
  *" -p org.mozilla.firefox "*)
    if [ -n "${FRAME_ROUTER_FIREFOX_STARTED:-}" ]; then
      : > "$FRAME_ROUTER_FIREFOX_STARTED"
    fi
    ;;
esac
if [ "${FRAME_ROUTER_BLOCK_AM:-0}" = 1 ]; then
  : > "$FRAME_ROUTER_AM_STARTED"
  while [ ! -f "$FRAME_ROUTER_RELEASE_AM" ]; do
    sleep 1
  done
fi
EOF

cat > "$fake_bin/input" <<'EOF'
#!/usr/bin/env sh
printf 'input %s\n' "$*" >> "$FRAME_ROUTER_LOG"
EOF

cat > "$fake_bin/sleep" <<'EOF'
#!/usr/bin/env sh
printf 'sleep %s\n' "${1:-0}" >> "$FRAME_ROUTER_LOG"
exit 0
EOF

cat > "$fake_bin/pidof" <<'EOF'
#!/usr/bin/env sh
if [ "${1:-}" = "de.ozerov.fully" ] && [ "${FRAME_ROUTER_FULLY_RUNNING:-0}" = 1 ]; then
  printf '%s\n' 4242
  exit 0
fi
if [ "${1:-}" = "org.mozilla.firefox" ] && [ "${FRAME_ROUTER_FIREFOX_RUNNING:-0}" = 1 ]; then
  printf '%s\n' 4343
  exit 0
fi
exit 1
EOF

cat > "$fake_bin/awk" <<'EOF'
#!/usr/bin/env sh
expected='{sub(/^.*\) /, ""); print $20}'
if [ "${1:-}" != "$expected" ]; then
  printf 'unexpected awk program: %s\n' "${1:-}" >&2
  exit 64
fi
printf '111\n'
EOF

chmod +x "$fake_bin"/*

config="$tmp_dir/router.conf"
frameos_config="$tmp_dir/frameos-router.conf"
state="$tmp_dir/state"
lock="$tmp_dir/router.lock"
log="$tmp_dir/commands.log"
dumpsys_calls="$tmp_dir/dumpsys-calls"
firefox_started="$tmp_dir/firefox-started"
cat > "$config" <<EOF
STATE_FILE=$state
LOCK_DIR=$lock
HOME_URL=https://home.test.invalid/lovelace/home
CAMERAS_URL=https://home.test.invalid/lovelace/cameras
CALENDAR_URL=https://home.test.invalid/lovelace/calendar
FULLY_ACTIVITY=de.ozerov.fully/.FullyActivity
FIREFOX_PACKAGE=org.mozilla.firefox
EOF

cat > "$frameos_config" <<EOF
STATE_FILE=$state
LOCK_DIR=$lock
HOME_URL=https://home.test.invalid/lovelace/home
CAMERAS_URL=https://home.test.invalid/lovelace/cameras
CALENDAR_URL=https://home.test.invalid/lovelace/calendar
FULLY_ACTIVITY=de.ozerov.fully/.FullyActivity
FIREFOX_PACKAGE=org.mozilla.firefox
FRAMEOS_PACKAGE=com.wyattfleming.frameos
FRAMEOS_ACTIVITY=com.wyattfleming.frameos/.MainActivity
FRAMEOS_RECEIVER=com.wyattfleming.frameos/.control.FrameControlReceiver
EOF

run_router() {
  rm -f "$firefox_started"
  if [[ "$1" == show ]]; then
    FRAME_ROUTER_PROC_ROOT="$proc_root" FRAME_ROUTER_LOG="$log" FRAME_ROUTER_FIREFOX_STARTED="$firefox_started" PATH="$fake_bin:$PATH" "$router_shell" "$router" show "$2" "$config" \
      >"$tmp_dir/stdout" 2>"$tmp_dir/stderr"
  else
    FRAME_ROUTER_PROC_ROOT="$proc_root" FRAME_ROUTER_LOG="$log" FRAME_ROUTER_FIREFOX_STARTED="$firefox_started" PATH="$fake_bin:$PATH" "$router_shell" "$router" "$1" "$config" \
      >"$tmp_dir/stdout" 2>"$tmp_dir/stderr"
  fi
}

run_router_expect_failure() {
  rm -f "$firefox_started"
  set +e
  FRAME_ROUTER_PROC_ROOT="$proc_root" FRAME_ROUTER_LOG="$log" FRAME_ROUTER_FIREFOX_STARTED="$firefox_started" PATH="$fake_bin:$PATH" "$router_shell" "$router" "$@" \
    >"$tmp_dir/stdout" 2>"$tmp_dir/stderr"
  local result=$?
  set -e
  [[ "$result" -ne 0 ]] || fail "expected failure for: $*"
}

assert_router_failure_contains() {
  local needle="$1"
  shift
  run_router_expect_failure "$@"
  assert_file_contains "$needle" "$tmp_dir/stderr" "router failure must explain the lock state"
}

run_frameos_router() {
  rm -f "$firefox_started"
  if [[ "$1" == show ]]; then
    FRAME_ROUTER_PROC_ROOT="$proc_root" FRAME_ROUTER_LOG="$log" FRAME_ROUTER_FIREFOX_STARTED="$firefox_started" PATH="$fake_bin:$PATH" "$router_shell" "$router" show "$2" "$frameos_config" \
      >"$tmp_dir/stdout" 2>"$tmp_dir/stderr"
  else
    FRAME_ROUTER_PROC_ROOT="$proc_root" FRAME_ROUTER_LOG="$log" FRAME_ROUTER_FIREFOX_STARTED="$firefox_started" PATH="$fake_bin:$PATH" "$router_shell" "$router" "$1" "$frameos_config" \
      >"$tmp_dir/stdout" 2>"$tmp_dir/stderr"
  fi
}

: > "$log"
printf 'photos\n' > "$state"
FRAME_ROUTER_FOREGROUND=fully run_router status
assert_eq photos "$(tr -d '\r\n' < "$tmp_dir/stdout")" \
  'Fully foreground must override saved mode'

printf 'cameras\n' > "$state"
FRAME_ROUTER_FOREGROUND=firefox run_router status
assert_eq cameras "$(tr -d '\r\n' < "$tmp_dir/stdout")" \
  'Firefox foreground must use the saved mode'

rm -f "$state"
FRAME_ROUTER_FOREGROUND=firefox run_router status
assert_eq home "$(tr -d '\r\n' < "$tmp_dir/stdout")" \
  'missing state must default to home'

printf 'not-a-mode\n' > "$state"
FRAME_ROUTER_FOREGROUND=unknown run_router status
assert_eq home "$(tr -d '\r\n' < "$tmp_dir/stdout")" \
  'corrupt state must default to home for an unknown foreground'

: > "$log"
printf 'photos\n' > "$state"
FRAME_ROUTER_FOREGROUND=frameos FRAME_ROUTER_FULLY_RUNNING=1 FRAME_ROUTER_FIREFOX_RUNNING=1 \
  run_frameos_router next
assert_file_contains 'am broadcast --user 0 -a com.wyattfleming.frameos.CONTROL -n com.wyattfleming.frameos/.control.FrameControlReceiver --es frameos.mode HOME' "$log" \
  'FrameOS next must advance photos to Home through the protected receiver'
assert_file_contains 'am force-stop de.ozerov.fully' "$log" \
  'FrameOS must stop Fully when its priority process is alive'
assert_file_contains 'am force-stop org.mozilla.firefox' "$log" \
  'FrameOS must release the legacy Firefox process when it is alive'
assert_file_contains 'am start --activity-reorder-to-front -n com.wyattfleming.frameos/.MainActivity' "$log" \
  'the shell router must foreground FrameOS after queuing its protected command'
assert_file_not_contains 'am start --activity-reorder-to-front -a android.intent.action.VIEW' "$log" \
  'FrameOS transitions must not launch Firefox'
assert_file_not_contains 'de.ozerov.fully/.FullyActivity' "$log" 'FrameOS Photos must not launch Fully'
assert_eq home "$(tr -d '\r\n' < "$state")" 'FrameOS next must persist Home'

: > "$log"
FRAME_ROUTER_FOREGROUND=frameos run_frameos_router next
assert_file_contains 'am broadcast --user 0 -a com.wyattfleming.frameos.CONTROL -n com.wyattfleming.frameos/.control.FrameControlReceiver --es frameos.mode WEATHER' "$log" \
  'FrameOS next must include the native Weather view'
assert_file_not_contains 'am force-stop de.ozerov.fully' "$log" \
  'warm FrameOS transitions must not pay for stopping an absent Fully process'
assert_file_not_contains 'am force-stop org.mozilla.firefox' "$log" \
  'warm FrameOS transitions must not pay for stopping an absent Firefox process'

: > "$log"
FRAME_ROUTER_FOREGROUND=frameos run_frameos_router next
assert_file_contains 'am broadcast --user 0 -a com.wyattfleming.frameos.CONTROL -n com.wyattfleming.frameos/.control.FrameControlReceiver --es frameos.mode CAMERAS' "$log" \
  'FrameOS next must advance Weather to Cameras'

: > "$log"
FRAME_ROUTER_FOREGROUND=frameos run_frameos_router next
assert_file_contains 'am broadcast --user 0 -a com.wyattfleming.frameos.CONTROL -n com.wyattfleming.frameos/.control.FrameControlReceiver --es frameos.mode CALENDAR' "$log" \
  'FrameOS next must advance Cameras to Calendar'

: > "$log"
FRAME_ROUTER_FOREGROUND=frameos run_frameos_router next
assert_file_contains 'am broadcast --user 0 -a com.wyattfleming.frameos.CONTROL -n com.wyattfleming.frameos/.control.FrameControlReceiver --es frameos.mode PHOTOS' "$log" \
  'FrameOS next must wrap Calendar to Photos inside FrameOS'

: > "$log"
printf 'photos\n' > "$state"
FRAME_ROUTER_FOREGROUND=frameos run_frameos_router prev
assert_file_contains 'am broadcast --user 0 -a com.wyattfleming.frameos.CONTROL -n com.wyattfleming.frameos/.control.FrameControlReceiver --es frameos.mode CALENDAR' "$log" \
  'FrameOS prev must wrap Photos to Calendar'

: > "$log"
FRAME_ROUTER_FOREGROUND=unknown run_frameos_router show weather
assert_file_contains 'am broadcast --user 0 -a com.wyattfleming.frameos.CONTROL -n com.wyattfleming.frameos/.control.FrameControlReceiver --es frameos.mode WEATHER' "$log" \
  'FrameOS direct Weather must use the protected receiver'
assert_file_not_contains 'dumpsys' "$log" \
  'FrameOS direct show must not inspect the current foreground mode'

: > "$log"
printf 'photos\n' > "$state"
FRAME_ROUTER_FOREGROUND=firefox run_router next
assert_file_contains 'am start --activity-reorder-to-front -a android.intent.action.VIEW -d https://home.test.invalid/lovelace/home -p org.mozilla.firefox' "$log" \
  'next must advance photos to home using the configured URL'
assert_file_contains 'input swipe 100 1000 100 500 100' "$log" \
  'Firefox navigation must perform exactly the required swipe'
assert_file_count 'input swipe 100 1000 100 500 100' "$log" 1 \
  'each Firefox launch must perform exactly one swipe'
assert_eq home "$(tr -d '\r\n' < "$state")" 'next must persist the selected mode'

: > "$log"
printf 'home\n' > "$state"
FRAME_ROUTER_FOREGROUND=firefox run_router next
assert_file_contains 'am start --activity-reorder-to-front -a android.intent.action.VIEW -d https://home.test.invalid/lovelace/cameras -p org.mozilla.firefox' "$log" \
  'next must advance home to cameras'
assert_file_count 'input swipe 100 1000 100 500 100' "$log" 1 \
  'home-to-cameras Firefox launch must perform exactly one swipe'

: > "$log"
printf 'cameras\n' > "$state"
FRAME_ROUTER_FOREGROUND=firefox run_router next
assert_file_contains 'am start --activity-reorder-to-front -a android.intent.action.VIEW -d https://home.test.invalid/lovelace/calendar -p org.mozilla.firefox' "$log" \
  'next must advance cameras to calendar'
assert_file_count 'input swipe 100 1000 100 500 100' "$log" 1 \
  'cameras-to-calendar Firefox launch must perform exactly one swipe'

: > "$log"
printf 'calendar\n' > "$state"
FRAME_ROUTER_FOREGROUND=firefox run_router next
assert_file_contains 'am start --activity-reorder-to-front -n de.ozerov.fully/.FullyActivity' "$log" \
  'next must wrap calendar to photos in Fully'

: > "$log"
printf 'photos\n' > "$state"
FRAME_ROUTER_FOREGROUND=firefox run_router prev
assert_file_contains 'am start --activity-reorder-to-front -a android.intent.action.VIEW -d https://home.test.invalid/lovelace/calendar -p org.mozilla.firefox' "$log" \
  'prev must wrap photos to calendar'
assert_file_count 'input swipe 100 1000 100 500 100' "$log" 1 \
  'photos-to-calendar Firefox launch must perform exactly one swipe'

: > "$log"
FRAME_ROUTER_FOREGROUND=unknown run_router show photos
assert_file_contains 'am start --activity-reorder-to-front -n de.ozerov.fully/.FullyActivity' "$log" \
  'photos must launch the configured Fully activity'
assert_file_not_contains 'org.mozilla.firefox' "$log" 'photos must not launch Firefox'

: > "$log"
rm -f "$dumpsys_calls"
FRAME_ROUTER_FOREGROUND=unknown FRAME_ROUTER_FIREFOX_READY_AFTER=0 \
  FRAME_ROUTER_DUMPSYS_CALLS="$dumpsys_calls" run_router show home
assert_eq 1 "$(cat "$dumpsys_calls")" \
  'direct Firefox show must make only its immediate readiness probe, not a current-mode probe'
assert_file_not_contains 'sleep 1' "$log" \
  'an immediately resumed Firefox activity must not pay the legacy fixed sleep'
assert_file_count 'input swipe 100 1000 100 500 100' "$log" 1 \
  'an immediately resumed Firefox activity must still receive one toolbar-collapse swipe'

: > "$log"
rm -f "$dumpsys_calls"
FRAME_ROUTER_FOREGROUND=unknown FRAME_ROUTER_FIREFOX_READY_AFTER=1 \
  FRAME_ROUTER_DUMPSYS_CALLS="$dumpsys_calls" run_router show cameras
assert_eq 2 "$(cat "$dumpsys_calls")" \
  'a delayed Firefox resume must be polled until it appears'
assert_file_count 'sleep 1' "$log" 1 \
  'a delayed Firefox resume must wait once between bounded probes'
assert_file_count 'input swipe 100 1000 100 500 100' "$log" 1 \
  'a delayed Firefox resume must receive one toolbar-collapse swipe after readiness'

: > "$log"
rm -f "$dumpsys_calls"
FRAME_ROUTER_FOREGROUND=unknown FRAME_ROUTER_FIREFOX_READY_AFTER=99 \
  FRAME_ROUTER_DUMPSYS_CALLS="$dumpsys_calls" run_router show calendar
assert_eq 4 "$(cat "$dumpsys_calls")" \
  'a never-ready Firefox activity must stop after the bounded readiness probe budget'
assert_file_count 'sleep 1' "$log" 3 \
  'a never-ready Firefox activity must use only bounded poll sleeps'
assert_file_not_contains 'input swipe 100 1000 100 500 100' "$log" \
  'a never-ready Firefox activity must not swipe an unrelated foreground surface'

: > "$log"
FRAME_ROUTER_FOREGROUND=background-firefox run_router show home
assert_file_count 'sleep 1' "$log" 3 \
  'a background Firefox task must not satisfy the resumed-activity readiness probe'
assert_file_not_contains 'input swipe 100 1000 100 500 100' "$log" \
  'a background Firefox task must not receive a toolbar-collapse swipe'

: > "$log"
FRAME_ROUTER_FOREGROUND=fully run_router next
assert_file_contains 'am start --activity-reorder-to-front -a android.intent.action.VIEW -d https://home.test.invalid/lovelace/home -p org.mozilla.firefox' "$log" \
  'next must treat Fully foreground as photos before advancing'
assert_file_count 'input swipe 100 1000 100 500 100' "$log" 1 \
  'Fully-foreground Firefox launch must perform exactly one swipe'

run_router_expect_failure unknown-action
run_router_expect_failure status "$tmp_dir/missing.conf"

# Android's Toybox awk rejects a doubled escape before the closing parenthesis.
# Exercise the real /proc parser path and require the single-escape ERE passed to
# awk rather than relying exclusively on the portable test override.
rm -f "$lock"
FRAME_ROUTER_PROCESS_START_TOKEN='' FRAME_ROUTER_FOREGROUND=unknown \
  run_router show photos
assert_eq photos "$(tr -d '\r\n' < "$state")" \
  'the Android-compatible process start parser must allow routing'

# A timeout can kill the shell without running its EXIT trap. A stale lock with
# a recorded dead owner must self-heal only after its lease has expired.
: > "$log"
printf '999999 111 1\n' > "$lock"
FRAME_ROUTER_FOREGROUND=unknown run_router show photos
assert_eq photos "$(tr -d '\r\n' < "$state")" \
  'an expired lock owned by a dead process must not strand routing'
[[ ! -e "$lock" ]] || fail 'the reclaimed stale lock must be released after the transition'

# Never reclaim a lock merely because its lease is old when the recorded owner
# is still alive. This guards PID+lease recovery against a slow live command.
: > "$log"
printf '%s 111 1\n' "$$" > "$lock"
assert_router_failure_contains 'live owner' show photos "$config"
assert_eq "$$ 111 1" "$(tr -d '\r\n' < "$lock")" \
  'a live lock owner must retain its lock even after the lease age'
rm -f "$lock"

# A dead owner inside the lease remains protected from a rapid stale-lock
# takeover; callers receive a retryable lock result instead.
printf '999999 111 %s\n' "$(date +%s)" > "$lock"
assert_router_failure_contains 'lease has not expired' show photos "$config"
rm -f "$lock"

# PID reuse is not ownership. A matching live PID with a different /proc start
# token is safely reclaimed once the old lock's lease expires.
printf '%s 222 1\n' "$$" > "$lock"
FRAME_ROUTER_FOREGROUND=unknown run_router show photos
[[ ! -e "$lock" ]] || fail 'a reused PID with a mismatched start token must not strand routing'

: > "$log"
rm -f "$state" "$tmp_dir/am-started" "$tmp_dir/release-am"
FRAME_ROUTER_PROC_ROOT="$proc_root" FRAME_ROUTER_FOREGROUND=firefox FRAME_ROUTER_BLOCK_AM=1 \
  FRAME_ROUTER_AM_STARTED="$tmp_dir/am-started" FRAME_ROUTER_RELEASE_AM="$tmp_dir/release-am" \
  FRAME_ROUTER_LOG="$log" PATH="$fake_bin:$PATH" "$router_shell" "$router" show home "$config" \
  >"$tmp_dir/first.stdout" 2>"$tmp_dir/first.stderr" &
first_pid=$!
for _ in $(seq 1 50); do
  [[ -f "$tmp_dir/am-started" ]] && break
  sleep 0.01
done
[[ -f "$tmp_dir/am-started" ]] || fail 'first invocation did not reach fake am'

run_router_expect_failure show cameras "$config"
touch "$tmp_dir/release-am"
wait "$first_pid"

printf 'frame-mode-router contract passed\n'
