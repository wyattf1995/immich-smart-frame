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

set +e
FRAME_READINESS_ACCESSIBILITY_STATE=suppressed FRAME_READINESS_LOG="$command_log" PATH="$fake_bin:$PATH" "$readiness_script" \
  --adb FRAME-TEST \
  --array-check-command 'exit 0' \
  --ha-check-command 'exit 0' > "$tmp_dir/suppressed-stdout" 2> "$tmp_dir/suppressed-stderr"
suppressed_result=$?
set -e
[[ "$suppressed_result" != 0 ]] || fail 'an active UI automation service suppressing Key Mapper must fail readiness'
grep -Fqx 'not ready: Frame input accessibility' "$tmp_dir/suppressed-stderr" || fail 'suppressed accessibility must identify the failed frame input check'

printf 'frame readiness contract passed\n'
