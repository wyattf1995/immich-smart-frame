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
        printf 'com.wyattfleming.frameos/.MainActivity\n'
        ;;
      'dumpsys activity activities')
        printf 'mResumedActivity: ActivityRecord{1 u0 com.wyattfleming.frameos/.MainActivity}\n'
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

printf 'frame readiness contract passed\n'
