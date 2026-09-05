#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
loop="$root/scripts/frame-health-loop.sh"
env_example="$root/scripts/frame-health-loop.env.example"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[[ -x "$loop" ]] || fail 'frame-health loop must be executable'
grep -Fq 'owner=$(stat -c' "$loop" || fail 'frame-health loop must validate config ownership'
grep -Fq '[[ "$owner" == "$(id -u)" || "$owner" == "0" ]]' "$loop" || fail 'frame-health loop must accept only current-user or root ownership'
grep -Fq 'export BIRDNET_BIND_IP BIRDNET_WATCHDOG_AUTH_FILE' "$loop" || fail 'frame-health loop must export watchdog auth-path settings to its Python child'
grep -Fq 'WEB_PORT' "$loop" || fail 'frame-health loop must preserve optional watchdog web-port configuration'
grep -Fq 'flock -n 9' "$loop" || fail 'frame-health loop must prevent duplicate publishers'
grep -Fq 'FRAME_HEALTH_LOCK_FILE' "$loop" || fail 'frame-health loop must allow its lock location to be isolated for verification'
grep -Fq 'trap ' "$loop" || fail 'frame-health loop must clean up on TERM or INT'
[[ $(grep -Fc 'frame-health publish failed' "$loop") -eq 1 ]] || fail 'frame-health loop must use one generic failure transition message'
grep -Fq 'docker run --rm -i --network host --read-only --user 0:0 --pull never --cap-drop ALL' "$loop" || fail 'frame-health loop must use the hardened ephemeral publisher container'
grep -Fq -- '--pids-limit 32 --cpus .1' "$loop" || fail 'frame-health loop must bound publisher resources'
grep -Fq -- '--evidence-stdin' "$loop" || fail 'frame-health loop must send bounded host evidence over stdin'
! grep -Fq '/var/run/docker.sock' "$loop" || fail 'frame-health loop must not mount the Docker socket'
grep -Fq 'FRAME_HEALTH_PYTHON_IMAGE=sha256:f3ac72983efcf1a310abe2ecb0ebeee84fefcb1a797668eac82697a43f8e3c5b' "$env_example" || fail 'sample loop config must pin the companion Python image digest'
! grep -Fq 'FRAME_HEALTH_TOKEN_FILE=' "$env_example" || fail 'sample loop config must not use the broad REST token transport'
grep -Fq 'FRAME_HEALTH_WEBHOOK_ID_FILE=' "$env_example" || fail 'sample loop config must use a scoped webhook ID file'
grep -Fq 'FRAME_HEALTH_CONTAINER_WEBHOOK_ID_FILE=' "$env_example" || fail 'sample loop config must define the webhook mount path'
grep -Fq 'BIRDNET_WATCHDOG_AUTH_FILE=' "$env_example" || fail 'sample loop config must provide the watchdog auth-file path'
printf 'PASS: frame-health loop invariants\n'

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
cat >"$tmp/bin/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat >"$tmp/bin/timeout" <<'EOF'
#!/usr/bin/env bash
shift
exec "$@"
EOF
cat >"$tmp/bin/docker" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  inspect) printf 'true|healthy|false|0\n' ;;
  run)
    printf '%s\n' "$@" >"$DOCKER_ARGS_LOG"
    cat >"$DOCKER_EVIDENCE_LOG"
    printf '%s\n' "$$" >"$DOCKER_CHILD_PID"
    exec sleep 30
    ;;
  *) exit 1 ;;
esac
EOF
cat >"$tmp/bin/watchdog" <<'EOF'
#!/usr/bin/env bash
printf '%s|%s|%s\n' "${BIRDNET_BIND_IP:-}" "${BIRDNET_WATCHDOG_AUTH_FILE:-}" "${WEB_PORT:-}" >>"$CHILD_ENV_LOG"
printf '%s\n' 'INFO: every reported audio source is HEALTHY' 'INFO: audio health fresh (0s <= 120s)'
EOF
chmod +x "$tmp/bin"/*
cp "$env_example" "$tmp/config"
printf 'FRAME_HEALTH_WATCHDOG=%s\nWEB_PORT=8090\n' "$tmp/bin/watchdog" >>"$tmp/config"
chmod 600 "$tmp/config"
CHILD_ENV_LOG="$tmp/child-env" DOCKER_ARGS_LOG="$tmp/docker-args" DOCKER_EVIDENCE_LOG="$tmp/evidence" DOCKER_CHILD_PID="$tmp/docker-child" FRAME_HEALTH_LOCK_FILE="$tmp/lock" PATH="$tmp/bin:$PATH" bash "$loop" "$tmp/config" >/dev/null 2>"$tmp/err" &
main_pid=$!
for _ in $(seq 1 50); do [[ -s "$tmp/evidence" ]] && break; sleep 0.05; done
[[ -s "$tmp/evidence" ]] || fail 'sample loop must send evidence to its publisher child'
expected='127.0.0.1|/mnt/user/appdata/birdnet-go/secrets/frame-watchdog-auth.json|8090'
[[ "$(cat "$tmp/child-env")" == "$expected" ]] || fail 'sample config watchdog settings must reach the watchdog child'
jq -e '.observedAt | type == "number"' "$tmp/evidence" >/dev/null || fail 'publisher evidence must contain observedAt'
jq -e '.watchdog.returncode == 0 and .containers["birdnet-go"].output == "true|healthy|false|0\n"' "$tmp/evidence" >/dev/null || fail 'publisher evidence must contain only fixed reports'
grep -Fxq -- '-i' "$tmp/docker-args" || fail 'publisher container must receive evidence stdin interactively'
grep -Fxq -- '--user' "$tmp/docker-args" || fail 'publisher container must run as root for the dedicated webhook mount'
grep -Fxq -- '0:0' "$tmp/docker-args" || fail 'publisher container user must be root'
grep -Fxq -- '--pull' "$tmp/docker-args" || fail 'publisher container must not pull at runtime'
grep -Fxq -- 'never' "$tmp/docker-args" || fail 'publisher container pull policy must be never'
grep -Fxq -- '--webhook-id-file' "$tmp/docker-args" || fail 'publisher container must use scoped webhook transport'
child_pid=$(cat "$tmp/docker-child")
kill -TERM "$main_pid"
wait "$main_pid" || true
if kill -0 "$child_pid" 2>/dev/null; then fail 'TERM must reap the owned publisher child'; fi
printf 'PASS: frame-health loop child environment and cleanup\n'
