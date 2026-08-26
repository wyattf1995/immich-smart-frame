#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
offline_dir="${KIOSK_OFFLINE_ASSETS_DIR:-$repo_root/offline-assets}"
expected_uid="${KIOSK_OFFLINE_UID:-65532}"
expected_gid="${KIOSK_OFFLINE_GID:-65532}"

fail() {
  printf 'offline-assets preflight failed: %s\n' "$*" >&2
  exit 1
}

[[ -d "$offline_dir" && ! -L "$offline_dir" ]] || fail "directory is missing or a symlink: $offline_dir"

if owner_uid="$(stat -c '%u' "$offline_dir" 2>/dev/null)"; then
  owner_gid="$(stat -c '%g' "$offline_dir")"
  mode="$(stat -c '%a' "$offline_dir")"
else
  owner_uid="$(stat -f '%u' "$offline_dir")"
  owner_gid="$(stat -f '%g' "$offline_dir")"
  mode="$(stat -f '%Lp' "$offline_dir")"
fi

[[ "$owner_uid" == "$expected_uid" ]] || fail "owner uid $owner_uid != required $expected_uid"
[[ "$owner_gid" == "$expected_gid" ]] || fail "owner gid $owner_gid != required $expected_gid"
[[ "$mode" == "700" ]] || fail "mode $mode != required 700"

printf 'offline-assets preflight passed: %s uid=%s gid=%s mode=%s\n' \
  "$offline_dir" "$owner_uid" "$owner_gid" "$mode"
