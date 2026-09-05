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
grep -Fq 'trap ' "$loop" || fail 'frame-health loop must clean up on TERM or INT'
[[ $(grep -Fc "frame-health publish failed" "$loop") -eq 1 ]] || fail 'frame-health loop must use one generic failure transition message'
grep -Fq 'docker run --rm --network host --read-only --cap-drop ALL' "$loop" || fail 'frame-health loop must use the hardened ephemeral publisher container'
grep -Fq -- '--pids-limit 32 --cpus .1' "$loop" || fail 'frame-health loop must bound publisher resources'
grep -Fq -- '--evidence-stdin' "$loop" || fail 'frame-health loop must send bounded host evidence over stdin'
! grep -Fq '/var/run/docker.sock' "$loop" || fail 'frame-health loop must not mount the Docker socket'
grep -Fq 'FRAME_HEALTH_PYTHON_IMAGE=sha256:f3ac72983efcf1a310abe2ecb0ebeee84fefcb1a797668eac82697a43f8e3c5b' "$env_example" || fail 'sample loop config must pin the companion Python image digest'
! grep -Eq '(^|[[:space:]])(HA_TOKEN|FRAME_HEALTH_TOKEN)=' "$env_example" || fail 'sample loop config must not contain token values'
grep -Fq 'BIRDNET_WATCHDOG_AUTH_FILE=' "$env_example" || fail 'sample loop config must provide the watchdog auth-file path'
printf 'PASS: frame-health loop invariants\n'
