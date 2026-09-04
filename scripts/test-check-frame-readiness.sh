#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readiness_script="$repo_root/scripts/check-frame-readiness.sh"

fail() {
  printf 'frame readiness contract failed: %s\n' "$*" >&2
  exit 1
}

[[ -x "$readiness_script" ]] || fail "missing executable $readiness_script"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/frame-readiness-test.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
fake_bin="$tmp_dir/bin"
command_log="$tmp_dir/commands.log"
mkdir -p "$fake_bin"

cat > "$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "$FRAME_READINESS_LOG"
case "$*" in
  *'/kiosk --livecheck'*|*'/kiosk --readycheck'*) exit 0 ;;
  *) exit 2 ;;
esac
EOF

cat > "$fake_bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -s && "$2" == FRAME-TEST ]] || exit 2
shift 2
case "$1" in
  get-state)
    printf 'device\n'
    ;;
  shell)
    shift
    printf 'adb shell %s\n' "$*" >> "$FRAME_READINESS_LOG"
    case "$*" in
      'pidof com.wyattfleming.frameos') printf '2468\n' ;;
      'pidof keymapper_sysbridge') exit 1 ;;
      'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
        printf 'Warning: intent resolution used the current user\n'
        printf 'com.wyattfleming.frameos/.MainActivity\r\n'
        ;;
      'dumpsys activity activities')
        printf 'mResumedActivity: ActivityRecord{1 u0 com.wyattfleming.frameos/.MainActivity}\n'
        ;;
      'dumpsys accessibility')
        if [[ "${FRAME_READINESS_ACCESSIBILITY_STATE:-ready}" == suppressed ]]; then
          printf 'User state[Ui Automation[eventTypes=TYPES_ALL_MASK]]\n'
          printf '     Bound services:{}\n'
          printf '     Enabled services:{{io.github.sds100.keymapper/io.github.sds100.keymapper.system.accessibility.MyAccessibilityService}}\n'
        else
          printf '     Bound services:{Service[label=Key Mapper, feedbackType[FEEDBACK_GENERIC]]}\n'
          printf '     Enabled services:{{io.github.sds100.keymapper/io.github.sds100.keymapper.system.accessibility.MyAccessibilityService}}\n'
        fi
        ;;
      'appops get com.wyattfleming.frameos SYSTEM_ALERT_WINDOW')
        if [[ "${FRAME_READINESS_OVERLAY_STATE:-allowed}" == denied ]]; then
          printf 'SYSTEM_ALERT_WINDOW: ignore\n'
        else
          printf 'SYSTEM_ALERT_WINDOW: allow; time=+2h14m3s ago\r\n'
        fi
        ;;
      'dumpsys duraspeed status')
        if [[ "${FRAME_READINESS_DURASPEED_STATE:-enabled}" == disabled ]]; then
          printf 'false\r\n'
        else
          printf 'true\r\n'
        fi
        ;;
      'dumpsys duraspeed config')
        if [[ "${FRAME_READINESS_DURASPEED_STATE:-enabled}" == missing-frameos ]]; then
          printf 'PlatformWhitelist: []\n'
          printf 'AppWhitelist: [org.mozilla.firefox, io.github.sds100.keymapper]\n'
        elif [[ "${FRAME_READINESS_DURASPEED_STATE:-enabled}" == platform-frameos ]]; then
          printf 'PlatformWhitelist: [com.wyattfleming.frameos]\n'
          printf 'AppWhitelist: [org.mozilla.firefox, io.github.sds100.keymapper]\n'
        else
          printf 'PlatformWhitelist: []\n'
          printf 'AppWhitelist: [org.mozilla.firefox, io.github.sds100.keymapper, com.wyattfleming.frameos]\r\n'
        fi
        ;;
      *) exit 2 ;;
    esac
    ;;
  *) exit 2 ;;
esac
EOF
chmod +x "$fake_bin/docker" "$fake_bin/adb"

set +e
FRAME_READINESS_LOG="$command_log" PATH="$fake_bin:$PATH" "$readiness_script" \
  --adb FRAME-TEST \
  --array-check-command 'exit 0' \
  --ha-check-command 'exit 0' > "$tmp_dir/stdout" 2> "$tmp_dir/stderr"
result=$?
set -e
[[ "$result" == 0 ]] || {
  cat "$tmp_dir/stdout" "$tmp_dir/stderr" >&2
  fail 'a missing optional Key Mapper sysbridge must not fail FrameOS readiness'
}

grep -Fqx 'ready: FrameOS default HOME component' "$tmp_dir/stdout" || fail 'readiness must verify the resolved HOME component'
grep -Fqx 'ready: FrameOS resumed activity' "$tmp_dir/stdout" || fail 'readiness must verify the resumed FrameOS activity'
grep -Fqx 'informational: Key Mapper sysbridge is not running (FrameOS direct keys remain available)' "$tmp_dir/stdout" || fail 'sysbridge absence must be reported as informational'
grep -Fq 'adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' "$command_log" || fail 'readiness must resolve Android HOME'
grep -Fq 'adb shell dumpsys activity activities' "$command_log" || fail 'readiness must inspect resumed Android activity'
grep -Fqx 'ready: Frame input accessibility' "$tmp_dir/stdout" || fail 'readiness must verify that configured input accessibility is bound'
grep -Fq 'adb shell dumpsys accessibility' "$command_log" || fail 'readiness must inspect the live accessibility binding'
grep -Fqx 'ready: FrameOS display-over-other-apps permission' "$tmp_dir/stdout" || \
  fail 'readiness must verify the overlay permission used by boot recovery'
grep -Fq 'adb shell appops get com.wyattfleming.frameos SYSTEM_ALERT_WINDOW' "$command_log" || \
  fail 'readiness must inspect FrameOS overlay app-ops without changing them'
grep -Fqx 'ready: FrameOS DuraSpeed policy' "$tmp_dir/stdout" || \
  fail 'readiness must verify the Lenovo background-lifetime policy'
grep -Fq 'adb shell dumpsys duraspeed status' "$command_log" || \
  fail 'readiness must inspect whether Lenovo DuraSpeed is active'
grep -Fq 'adb shell dumpsys duraspeed config' "$command_log" || \
  fail 'enabled DuraSpeed must be checked for the exact FrameOS package exemption'

set +e
FRAME_READINESS_ACCESSIBILITY_STATE=suppressed FRAME_READINESS_LOG="$command_log" PATH="$fake_bin:$PATH" "$readiness_script" \
  --adb FRAME-TEST \
  --array-check-command 'exit 0' \
  --ha-check-command 'exit 0' > "$tmp_dir/suppressed-stdout" 2> "$tmp_dir/suppressed-stderr"
suppressed_result=$?
set -e
[[ "$suppressed_result" != 0 ]] || fail 'an active UI automation service suppressing Key Mapper must fail readiness'
grep -Fqx 'not ready: Frame input accessibility' "$tmp_dir/suppressed-stderr" || fail 'suppressed accessibility must identify the failed frame input check'

set +e
FRAME_READINESS_OVERLAY_STATE=denied FRAME_READINESS_LOG="$command_log" PATH="$fake_bin:$PATH" "$readiness_script" \
  --adb FRAME-TEST \
  --array-check-command 'exit 0' \
  --ha-check-command 'exit 0' > "$tmp_dir/overlay-stdout" 2> "$tmp_dir/overlay-stderr"
overlay_result=$?
set -e
[[ "$overlay_result" != 0 ]] || fail 'a denied display-over-other-apps app-op must fail readiness'
grep -Fqx 'not ready: FrameOS display-over-other-apps permission' "$tmp_dir/overlay-stderr" || \
  fail 'denied overlay permission must identify the failed boot-recovery prerequisite'

set +e
FRAME_READINESS_DURASPEED_STATE=missing-frameos FRAME_READINESS_LOG="$command_log" PATH="$fake_bin:$PATH" "$readiness_script" \
  --adb FRAME-TEST \
  --array-check-command 'exit 0' \
  --ha-check-command 'exit 0' > "$tmp_dir/duraspeed-stdout" 2> "$tmp_dir/duraspeed-stderr"
duraspeed_result=$?
set -e
[[ "$duraspeed_result" != 0 ]] || fail 'enabled DuraSpeed without FrameOS in its whitelist must fail readiness'
grep -Fqx 'not ready: FrameOS DuraSpeed policy' "$tmp_dir/duraspeed-stderr" || \
  fail 'missing DuraSpeed exemption must identify the failed frame policy check'

FRAME_READINESS_DURASPEED_STATE=platform-frameos FRAME_READINESS_LOG="$command_log" PATH="$fake_bin:$PATH" "$readiness_script" \
  --adb FRAME-TEST \
  --array-check-command 'exit 0' \
  --ha-check-command 'exit 0' > "$tmp_dir/duraspeed-platform-stdout" 2> "$tmp_dir/duraspeed-platform-stderr" || {
    cat "$tmp_dir/duraspeed-platform-stdout" "$tmp_dir/duraspeed-platform-stderr" >&2
    fail 'the firmware-supported platform whitelist must satisfy the FrameOS exemption'
  }
grep -Fqx 'ready: FrameOS DuraSpeed policy' "$tmp_dir/duraspeed-platform-stdout" || \
  fail 'a platform-whitelisted FrameOS package must report a ready policy'

FRAME_READINESS_DURASPEED_STATE=disabled FRAME_READINESS_LOG="$command_log" PATH="$fake_bin:$PATH" "$readiness_script" \
  --adb FRAME-TEST \
  --array-check-command 'exit 0' \
  --ha-check-command 'exit 0' > "$tmp_dir/duraspeed-disabled-stdout" 2> "$tmp_dir/duraspeed-disabled-stderr" || {
    cat "$tmp_dir/duraspeed-disabled-stdout" "$tmp_dir/duraspeed-disabled-stderr" >&2
    fail 'globally disabled DuraSpeed must not require a package exemption'
  }
grep -Fqx 'ready: FrameOS DuraSpeed policy' "$tmp_dir/duraspeed-disabled-stdout" || \
  fail 'globally disabled DuraSpeed must report a ready policy'

if grep -Eq 'adb shell (appops set|settings put|am |force-stop|reboot)' "$command_log"; then
  fail 'readiness must never mutate frame permissions, settings, activities, or power state'
fi

printf 'frame readiness contract passed\n'
