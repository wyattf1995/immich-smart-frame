#!/bin/bash
# Root-owned deployment environment supplies host, user, key, and fixed listener.
set -eu
source "${FRAME_TUNNEL_ENV:-/boot/config/frame-companion-tunnel.env}"
: "${FRAME_TUNNEL_HOST:?}" "${FRAME_TUNNEL_USER:?}" "${FRAME_TUNNEL_KEY:?}"
: "${FRAME_TUNNEL_KNOWN_HOSTS:?}" "${FRAME_TUNNEL_FORWARD:?}"
exec 9>/var/lock/frame-companion-tunnel.lock
/usr/bin/flock -n 9 || exit 0
child=''
cleanup() { if [[ -n "$child" ]]; then kill "$child" 2>/dev/null || true; fi; }
trap cleanup EXIT
trap 'exit 0' INT TERM
delay=5
while true; do
  if ! /usr/bin/curl -fsS --connect-timeout 2 --max-time 4 http://127.0.0.1:8092/healthz >/dev/null; then
    sleep 15
    continue
  fi
  started=$(date +%s)
  /usr/bin/ssh -NT -o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 \
    -o ExitOnForwardFailure=yes -o GlobalKnownHostsFile=/dev/null \
    -o IdentitiesOnly=yes -o KbdInteractiveAuthentication=no -o LogLevel=ERROR \
    -o PasswordAuthentication=no -o PreferredAuthentications=publickey \
    -o RequestTTY=no -o ServerAliveCountMax=3 -o ServerAliveInterval=15 \
    -o StrictHostKeyChecking=yes -o TCPKeepAlive=yes \
    -o UserKnownHostsFile="$FRAME_TUNNEL_KNOWN_HOSTS" \
    -i "$FRAME_TUNNEL_KEY" -R "$FRAME_TUNNEL_FORWARD" \
    "$FRAME_TUNNEL_USER@$FRAME_TUNNEL_HOST" &
  child=$!
  wait "$child" || true
  child=''
  elapsed=$(( $(date +%s) - started ))
  sleep "$delay"
  if (( elapsed >= 60 )); then delay=5; else delay=$(( delay * 2 )); fi
  (( delay <= 60 )) || delay=60
done
