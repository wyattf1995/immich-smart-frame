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
snapshot_parent="$(mktemp -d "${TMPDIR:-/tmp}/deployment-input-snapshot-store.XXXXXX")"
trap 'rm -rf "$tmp_dir" "$snapshot_parent"' EXIT

mkdir -p "$tmp_dir/config" "$tmp_dir/secrets" "$tmp_dir/offline-assets"
printf '%s\n' 'IMMICH_URL=http://immich.example.invalid:2283' > "$tmp_dir/.env"
printf '%s\n' 'KIOSK_CONFIG_FILE=./config/config.yaml' >> "$tmp_dir/.env"
printf '%s\n' 'KIOSK_API_KEY_FILE=./secrets/immich_api_key' >> "$tmp_dir/.env"
printf '%s\n' 'curation_profile: balanced' > "$tmp_dir/config/config.yaml"
printf '%s\n' 'not-a-real-secret' > "$tmp_dir/secrets/immich_api_key"
printf '%s\n' 'offline fixture' > "$tmp_dir/offline-assets/fixture.txt"

snapshot_dir="$snapshot_parent/snapshot"
"$snapshot_script" create "$snapshot_dir" "$tmp_dir/.env"
snapshot_mode="$(if stat -c '%a' "$snapshot_dir" >/dev/null 2>&1; then stat -c '%a' "$snapshot_dir"; else stat -f '%Lp' "$snapshot_dir"; fi)"
[[ "$snapshot_mode" == "700" ]] || fail "snapshot directory must be mode 700, got $snapshot_mode"
[[ -f "$snapshot_dir/manifest.sha256" ]] || fail 'snapshot must record input hashes'
[[ -f "$snapshot_dir/inputs/.env" ]] || fail 'snapshot must retain the deployment environment file'
[[ -f "$snapshot_dir/inputs/config/config.yaml" ]] || fail 'snapshot must retain the active config'
[[ -f "$snapshot_dir/inputs/secrets/immich_api_key" ]] || fail 'snapshot must retain the API-key secret'
secret_mode="$(if stat -c '%a' "$snapshot_dir/inputs/secrets/immich_api_key" >/dev/null 2>&1; then stat -c '%a' "$snapshot_dir/inputs/secrets/immich_api_key"; else stat -f '%Lp' "$snapshot_dir/inputs/secrets/immich_api_key"; fi)"
[[ "$secret_mode" == "600" ]] || fail "snapshot API-key secret must be mode 600, got $secret_mode"
[[ -f "$snapshot_dir/inputs/offline-assets/fixture.txt" ]] || fail 'snapshot must retain offline assets'
"$snapshot_script" verify "$snapshot_dir" "$tmp_dir/.env"

if "$snapshot_script" restore "$snapshot_dir" "$tmp_dir/.env"; then
  fail 'restore must require the exact --confirm-restore token'
fi

printf '%s\n' 'changed: true' >> "$tmp_dir/config/config.yaml"
if "$snapshot_script" verify "$snapshot_dir" "$tmp_dir/.env"; then
  fail 'verification must reject a deployment input that differs from the snapshot'
fi

printf '%s\n' 'new offline fixture' > "$tmp_dir/offline-assets/new.txt"
"$snapshot_script" restore "$snapshot_dir" "$tmp_dir/.env" --confirm-restore
"$snapshot_script" verify "$snapshot_dir" "$tmp_dir/.env"
[[ ! -e "$tmp_dir/offline-assets/new.txt" ]] || fail 'restore must remove offline state absent from the snapshot'
grep -Fxq 'curation_profile: balanced' "$tmp_dir/config/config.yaml" || fail 'restore must recover the saved config'

# Verify and restore must validate saved payload integrity before reporting a
# match or overwriting any live deployment input. A failure after copying is too
# late, and SHA-256 alone is not an attacker-authentication mechanism.
payload_snapshot="$snapshot_parent/payload-corruption"
"$snapshot_script" create "$payload_snapshot" "$tmp_dir/.env"
printf '%s\n' 'tampered snapshot payload' >> "$payload_snapshot/inputs/config/config.yaml"
if "$snapshot_script" verify "$payload_snapshot" "$tmp_dir/.env"; then
  fail 'verify must reject a payload whose hash no longer matches the manifest'
fi
printf '%s\n' 'must-survive-payload-rejection' >> "$tmp_dir/config/config.yaml"
if "$snapshot_script" restore "$payload_snapshot" "$tmp_dir/.env" --confirm-restore; then
  fail 'restore must reject a payload whose hash no longer matches the manifest'
fi
grep -Fxq 'must-survive-payload-rejection' "$tmp_dir/config/config.yaml" || fail 'a corrupted snapshot must not overwrite live config before rejection'
printf '%s\n' 'curation_profile: balanced' > "$tmp_dir/config/config.yaml"

manifest_snapshot="$snapshot_parent/manifest-path-corruption"
"$snapshot_script" create "$manifest_snapshot" "$tmp_dir/.env"
printf '%064d  ../outside.yaml\n' 0 >> "$manifest_snapshot/manifest.sha256"
printf '%s\n' 'must-survive-manifest-rejection' >> "$tmp_dir/config/config.yaml"
if "$snapshot_script" restore "$manifest_snapshot" "$tmp_dir/.env" --confirm-restore; then
  fail 'restore must reject manifest paths outside the snapshot payload root'
fi
grep -Fxq 'must-survive-manifest-rejection' "$tmp_dir/config/config.yaml" || fail 'an unsafe manifest path must not overwrite live config before rejection'
printf '%s\n' 'curation_profile: balanced' > "$tmp_dir/config/config.yaml"

extra_snapshot="$snapshot_parent/extra-payload-file"
"$snapshot_script" create "$extra_snapshot" "$tmp_dir/.env"
printf '%s\n' 'unexpected payload' > "$extra_snapshot/inputs/offline-assets/unlisted.txt"
if "$snapshot_script" restore "$extra_snapshot" "$tmp_dir/.env" --confirm-restore; then
  fail 'restore must reject payload files not listed in the manifest'
fi
[[ ! -e "$tmp_dir/offline-assets/unlisted.txt" ]] || fail 'an unlisted payload file must not reach live offline-assets'

outside_config="$tmp_dir/outside.yaml"
printf '%s\n' 'outside: true' > "$outside_config"
printf '%s\n' 'KIOSK_CONFIG_FILE=./config/../outside.yaml' > "$tmp_dir/path-traversal.env"
printf '%s\n' 'KIOSK_API_KEY_FILE=./secrets/immich_api_key' >> "$tmp_dir/path-traversal.env"
if "$snapshot_script" create "$tmp_dir/path-traversal-snapshot" "$tmp_dir/path-traversal.env"; then
  fail 'snapshot must reject inputs outside the deployment root'
fi

ln -s "$tmp_dir/config/config.yaml" "$tmp_dir/config-link.yaml"
printf '%s\n' 'KIOSK_CONFIG_FILE=./config-link.yaml' > "$tmp_dir/symlink.env"
printf '%s\n' 'KIOSK_API_KEY_FILE=./secrets/immich_api_key' >> "$tmp_dir/symlink.env"
if "$snapshot_script" create "$tmp_dir/symlink-snapshot" "$tmp_dir/symlink.env"; then
  fail 'snapshot must reject symlinked private inputs'
fi

ln -s "$tmp_dir/config/config.yaml" "$tmp_dir/offline-assets/linked-config.yaml"
if "$snapshot_script" create "$tmp_dir/offline-symlink-snapshot" "$tmp_dir/.env"; then
  fail 'snapshot must reject symlinks inside offline-assets before an exact restore can follow them'
fi
rm "$tmp_dir/offline-assets/linked-config.yaml"

if "$snapshot_script" create "$tmp_dir/deployment-snapshot" "$tmp_dir/.env"; then
  fail 'snapshot must reject a target located inside deployment inputs'
fi

chmod 755 "$snapshot_dir"
if "$snapshot_script" verify "$snapshot_dir" "$tmp_dir/.env"; then
  fail 'verification must reject a non-private snapshot directory'
fi
chmod 700 "$snapshot_dir"
"$snapshot_script" verify "$snapshot_dir" "$tmp_dir/.env"

printf 'deployment input snapshot contract passed\n'
