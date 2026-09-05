#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
watchdog="$root/scripts/birdnet-watchdog.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
cat >"$tmp/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
input=$(cat)
printf '%s\n' "$*" >>"$CURL_LOG"
case "$* $input" in
  *'/api/v2/auth/login'*) printf '%s' '{"success":true,"redirectUrl":"/api/v2/auth/callback?code=one&redirect=%2F"}' ;;
  *'/api/v2/auth/callback?'*) printf 'Set-Cookie: session=opaque; Path=/; HttpOnly\r\n\r\n' ;;
  *'/api/v2/health/audio'*) printf '%s' '{"sources":[{"state":"HEALTHY","audio_age_seconds":1}]}' ;;
  *) exit 1 ;;
esac
EOF
cat >"$tmp/bin/docker" <<'EOF'
#!/usr/bin/env bash
case "$*" in *'.Id'*) exit 1;; *'RestartCount'*) printf 0;; esac
EOF
cat >"$tmp/bin/df" <<'EOF'
#!/usr/bin/env bash
printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/x 1 1 1 1%% /data\n'
EOF
chmod +x "$tmp/bin"/*
printf '%s' '{"clientid":"birdnet-client","password":"not-in-argv"}' >"$tmp/auth.json"
chmod 600 "$tmp/auth.json"
CURL_LOG="$tmp/curl.log" PATH="$tmp/bin:$PATH" BIRDNET_BIND_IP=127.0.0.1 BIRDNET_WATCHDOG_AUTH_FILE="$tmp/auth.json" bash "$watchdog" >"$tmp/out"
grep -Fq 'audio health fresh' "$tmp/out"
grep -Fq '/api/v2/auth/login' "$tmp/curl.log"
grep -Fq '/api/v2/auth/callback?' "$tmp/curl.log"
grep -Fq '/api/v2/health/audio' "$tmp/curl.log"
! grep -Fq 'not-in-argv' "$tmp/curl.log"
printf 'PASS: authenticated watchdog flow\n'
