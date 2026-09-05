#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
watchdog="$root/scripts/birdnet-watchdog.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
real_jq=$(command -v jq)
cat >"$tmp/bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
input=$(cat)
printf '%s\n' "$*" >>"$CURL_LOG"
case "$* $input" in
  *'/api/v2/auth/login'*)
    if [[ -n "${LOGIN_RESPONSE:-}" ]]; then
      printf '%s' "$LOGIN_RESPONSE"
    else
      printf '%s' '{"success":true,"redirectUrl":"/api/v2/auth/callback?code=one&redirect=%2F"}'
    fi
    ;;
  *'/api/v2/auth/callback?'*)
    printf 'Set-Cookie: csrf=not-the-session; Path=/\r\nSet-Cookie: _gothic_session=opaque|signature; Path=/; HttpOnly\r\n\r\n'
    ;;
  *'/api/v2/health/audio'*)
    [[ "$input" == *'Cookie: _gothic_session=opaque|signature'* ]] || exit 1
    printf '%s' '{"sources":[{"state":"HEALTHY","audio_age_seconds":1}]}'
    ;;
  *) exit 1 ;;
esac
CURL
cat >"$tmp/bin/jq" <<'JQ'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$JQ_LOG"
exec "$REAL_JQ" "$@"
JQ
cat >"$tmp/bin/docker" <<'EOF_DOCKER'
#!/usr/bin/env bash
case "$*" in *'.Id'*) exit 1;; *'RestartCount'*) printf 0;; esac
EOF_DOCKER
cat >"$tmp/bin/df" <<'EOF_DF'
#!/usr/bin/env bash
printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/x 1 1 1 1%% /data\n'
EOF_DF
chmod +x "$tmp/bin"/*
printf '%s' '{"clientid":"birdnet-client","password":"not-in-argv"}' >"$tmp/auth.json"
chmod 600 "$tmp/auth.json"

run_watchdog() {
  CURL_LOG="$tmp/curl.log" JQ_LOG="$tmp/jq.log" REAL_JQ="$real_jq" PATH="$tmp/bin:$PATH" BIRDNET_BIND_IP=127.0.0.1 BIRDNET_WATCHDOG_AUTH_FILE="$tmp/auth.json" bash "$watchdog"
}

run_watchdog >"$tmp/out" 2>"$tmp/err" || { cat "$tmp/out" "$tmp/err" >&2; exit 1; }
grep -Fq 'audio health fresh' "$tmp/out"
[[ $(wc -l <"$tmp/curl.log") -eq 3 ]]
grep -Fq '/api/v2/auth/login' "$tmp/curl.log"
! grep -Fq 'not-in-argv' "$tmp/curl.log"
! grep -Fq 'code=one' "$tmp/curl.log"
! grep -Fq 'not-in-argv' "$tmp/jq.log"

assert_rejected_login_response() {
  local response="$1" label="$2"
  : >"$tmp/curl.log"; : >"$tmp/jq.log"
  if LOGIN_RESPONSE="$response" run_watchdog >"$tmp/$label.out" 2>"$tmp/$label.err"; then
    cat "$tmp/$label.out" "$tmp/$label.err" >&2
    exit 1
  fi
  [[ $(wc -l <"$tmp/curl.log") -eq 1 ]]
  grep -Fq '/api/v2/auth/login' "$tmp/curl.log"
  ! grep -Fq 'not-in-argv' "$tmp/$label.out"
  ! grep -Fq 'not-in-argv' "$tmp/$label.err"
}

assert_rejected_login_response '{"success":true,"redirectUrl":"https://invalid.example/api/v2/auth/callback?code=one"}' off-origin
assert_rejected_login_response '{"success":true,"redirectUrl":"/api/v2/auth/callback?code=one\\"evil"}' quote
assert_rejected_login_response '{"success":true,"redirectUrl":"/api/v2/auth/callback?code=one\\\\evil"}' backslash
assert_rejected_login_response '{"success":true,"redirectUrl":"/api/v2/auth/callback?code=one\\r\\nevil"}' crlf
long=$(printf '%*s' 2049 ''); long=${long// /a}
assert_rejected_login_response "$(printf '{\"success\":true,\"redirectUrl\":\"/api/v2/auth/callback?code=%s\"}' "$long")" oversized
assert_rejected_login_response 'not-json' bad-json

printf '%s' 'not-json' >"$tmp/malformed.json"
if CURL_LOG="$tmp/curl.log" JQ_LOG="$tmp/jq.log" REAL_JQ="$real_jq" PATH="$tmp/bin:$PATH" BIRDNET_BIND_IP=127.0.0.1 BIRDNET_WATCHDOG_AUTH_FILE="$tmp/malformed.json" bash "$watchdog" >"$tmp/malformed.out" 2>"$tmp/malformed.err"; then
  cat "$tmp/malformed.out" "$tmp/malformed.err" >&2
  exit 1
fi

chmod 644 "$tmp/auth.json"
if run_watchdog >"$tmp/insecure-mode.out" 2>"$tmp/insecure-mode.err"; then
  cat "$tmp/insecure-mode.out" "$tmp/insecure-mode.err" >&2
  exit 1
fi
printf 'PASS: authenticated watchdog flow\n'
