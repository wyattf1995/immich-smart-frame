#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/offline-assets-permissions.XXXXXX")"
offline_dir="$tmp_root/offline-assets"
uid="$(id -u)"
gid="$(id -g)"

fail() {
  printf 'offline-assets permission test failed: %s\n' "$*" >&2
  exit 1
}

trap 'rm -rf "$tmp_root"' EXIT
mkdir -p "$offline_dir"
chmod 700 "$offline_dir"
KIOSK_OFFLINE_ASSETS_DIR="$offline_dir" KIOSK_OFFLINE_UID="$uid" KIOSK_OFFLINE_GID="$gid" \
  "$repo_root/scripts/check-offline-assets-permissions.sh" >/dev/null || fail 'private directory rejected'

chmod 755 "$offline_dir"
if KIOSK_OFFLINE_ASSETS_DIR="$offline_dir" KIOSK_OFFLINE_UID="$uid" KIOSK_OFFLINE_GID="$gid" \
  "$repo_root/scripts/check-offline-assets-permissions.sh" >/dev/null 2>&1; then
  fail 'world-readable directory accepted'
fi

printf 'offline-assets permission contract passed\n'
