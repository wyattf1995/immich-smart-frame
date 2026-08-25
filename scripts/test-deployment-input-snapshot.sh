#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
snapshot_script="$repo_root/scripts/deployment-input-snapshot.sh"

fail() {
  printf 'deployment input snapshot contract failed: %s\n' "$*" >&2
  exit 1
}

[[ -x "$snapshot_script" ]] || fail "missing executable $snapshot_script"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/deployment-input-snapshot-test.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/config" "$tmp_dir/secrets" "$tmp_dir/offline-assets"
printf '%s\n' 'IMMICH_URL=http://immich.example.invalid:2283' > "$tmp_dir/.env"
printf '%s\n' 'KIOSK_CONFIG_FILE=./config/config.yaml' >> "$tmp_dir/.env"
printf '%s\n' 'KIOSK_API_KEY_FILE=./secrets/immich_api_key' >> "$tmp_dir/.env"
printf '%s\n' 'curation_profile: balanced' > "$tmp_dir/config/config.yaml"
printf '%s\n' 'not-a-real-secret' > "$tmp_dir/secrets/immich_api_key"
printf '%s\n' 'offline fixture' > "$tmp_dir/offline-assets/fixture.txt"

snapshot_dir="$tmp_dir/snapshot"
"$snapshot_script" create "$snapshot_dir" "$tmp_dir/.env"
[[ -f "$snapshot_dir/manifest.sha256" ]] || fail 'snapshot must record input hashes'
[[ -f "$snapshot_dir/inputs/.env" ]] || fail 'snapshot must retain the deployment environment file'
[[ -f "$snapshot_dir/inputs/config/config.yaml" ]] || fail 'snapshot must retain the active config'
[[ -f "$snapshot_dir/inputs/secrets/immich_api_key" ]] || fail 'snapshot must retain the API-key secret'
[[ -f "$snapshot_dir/inputs/offline-assets/fixture.txt" ]] || fail 'snapshot must retain offline assets'
"$snapshot_script" verify "$snapshot_dir" "$tmp_dir/.env"

printf '%s\n' 'changed: true' >> "$tmp_dir/config/config.yaml"
if "$snapshot_script" verify "$snapshot_dir" "$tmp_dir/.env"; then
  fail 'verification must reject a deployment input that differs from the snapshot'
fi

printf '%s\n' 'new offline fixture' > "$tmp_dir/offline-assets/new.txt"
"$snapshot_script" restore "$snapshot_dir" "$tmp_dir/.env" --confirm-restore
"$snapshot_script" verify "$snapshot_dir" "$tmp_dir/.env"
[[ ! -e "$tmp_dir/offline-assets/new.txt" ]] || fail 'restore must remove offline state absent from the snapshot'
grep -Fxq 'curation_profile: balanced' "$tmp_dir/config/config.yaml" || fail 'restore must recover the saved config'

printf 'deployment input snapshot contract passed\n'
