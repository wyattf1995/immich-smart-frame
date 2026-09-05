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
! grep -Eq '(^|[[:space:]])(HA_TOKEN|FRAME_HEALTH_TOKEN)=' "$env_example" || fail 'sample loop config must not contain token values'
grep -Fq 'BIRDNET_WATCHDOG_AUTH_FILE=' "$env_example" || fail 'sample loop config must provide the watchdog auth-file path'
printf 'PASS: frame-health loop invariants\n'
