#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'usage: %s {create|verify} SNAPSHOT_DIR ENV_FILE\n' "$0" >&2
  printf '       %s restore SNAPSHOT_DIR ENV_FILE --confirm-restore\n' "$0" >&2
  exit 2
}

fail() {
  printf 'deployment-input-snapshot: %s\n' "$*" >&2
  exit 1
}

[[ $# -ge 3 ]] || usage
action="$1"
snapshot_dir="$2"
env_file="$3"
case "$action" in
  create|verify) [[ $# -eq 3 ]] || usage ;;
  restore) [[ $# -eq 4 && "${4:-}" == '--confirm-restore' ]] || usage ;;
  *) usage ;;
esac
[[ -f "$env_file" && ! -L "$env_file" ]] || fail "environment file must be a regular non-symlink file: $env_file"

env_file="$(cd -P "$(dirname "$env_file")" && pwd -P)/$(basename "$env_file")"
deployment_root="$(dirname "$env_file")"
[[ "$deployment_root" != / ]] || fail 'environment file must not live at filesystem root'

reject_unsafe_path() {
  local value="${1#./}"
  case "/$value/" in
    */../*|*/./*) fail "path must not contain . or .. components: $value" ;;
  esac
}

canonical_existing_path() {
  local candidate="$1"
  local parent
  [[ -e "$candidate" || -L "$candidate" ]] || fail "input does not exist: $candidate"
  parent="$(cd -P "$(dirname "$candidate")" && pwd -P)"
  printf '%s/%s\n' "$parent" "$(basename "$candidate")"
}

assert_within_deployment_root() {
  local source="$1"
  case "$source" in
    "$deployment_root"/*) ;;
    *) fail "input must be below the environment-file directory: $source" ;;
  esac
}

assert_no_symlink_path() {
  local source="$1"
  local relative current segment
  [[ ! -L "$source" ]] || fail "input must not be a symlink: $source"
  relative="${source#"$deployment_root"/}"
  current="$deployment_root"
  IFS=/ read -r -a path_segments <<< "$relative"
  for segment in "${path_segments[@]}"; do
    current="$current/$segment"
    [[ ! -L "$current" ]] || fail "input path contains a symlink: $current"
  done
}

env_value_from() {
  local source_file="$1"
  local key="$2"
  local default_value="$3"
  local line
  line="$(sed -n "s/^${key}=//p" "$source_file" | tail -n 1)"
  if [[ -z "$line" ]]; then
    printf '%s\n' "$default_value"
  else
    case "$line" in
      \"*\"|\'*\') line="${line:1:${#line}-2}" ;;
    esac
    printf '%s\n' "$line"
  fi
}

env_value() {
  env_value_from "$env_file" "$1" "$2"
}

resolve_input_path() {
  local value="$1"
  local candidate
  reject_unsafe_path "$value"
  case "$value" in
    /*) candidate="$value" ;;
    *) candidate="$deployment_root/${value#./}" ;;
  esac
  candidate="$(canonical_existing_path "$candidate")"
  assert_within_deployment_root "$candidate"
  assert_no_symlink_path "$candidate"
  printf '%s\n' "$candidate"
}

snapshot_relative_path() {
  local source="$1"
  assert_within_deployment_root "$source"
  printf '%s\n' "${source#"$deployment_root"/}"
}

config_file="$(resolve_input_path "$(env_value KIOSK_CONFIG_FILE ./config/config.yaml)")"
secret_file="$(resolve_input_path "$(env_value KIOSK_API_KEY_FILE ./secrets/immich_api_key)")"
offline_assets="$deployment_root/offline-assets"
for required_file in "$env_file" "$config_file" "$secret_file"; do
  [[ -f "$required_file" && ! -L "$required_file" ]] || fail "required input must be a regular non-symlink file: $required_file"
done
[[ -d "$offline_assets" && ! -L "$offline_assets" ]] || fail "offline-assets must be a non-symlink directory: $offline_assets"
if [[ -n "$(find "$offline_assets" -type l -print -quit)" ]]; then
  fail 'offline-assets must not contain symlinks'
fi

normalize_snapshot_path() {
  local candidate="$1"
  local parent normalized
  reject_unsafe_path "$candidate"
  [[ -d "$(dirname "$candidate")" ]] || fail "snapshot parent directory must already exist: $(dirname "$candidate")"
  parent="$(cd -P "$(dirname "$candidate")" && pwd -P)"
  normalized="$parent/$(basename "$candidate")"
  [[ "$normalized" != / && "$normalized" != "$deployment_root" && "$normalized" != "$deployment_root"/* ]] || \
    fail "snapshot must live outside deployment inputs: $normalized"
  printf '%s\n' "$normalized"
}

snapshot_dir="$(normalize_snapshot_path "$snapshot_dir")"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

write_manifest() {
  local output="$1"
  : > "$output"
  for source in "$env_file" "$config_file" "$secret_file"; do
    printf '%s  %s\n' "$(sha256 "$source")" "$(snapshot_relative_path "$source")" >> "$output"
  done
  while IFS= read -r source; do
    printf '%s  %s\n' "$(sha256 "$source")" "$(snapshot_relative_path "$source")" >> "$output"
  done < <(find "$offline_assets" -type f -print | LC_ALL=C sort)
}

assert_manifest_relative_path() {
  local path="$1"
  case "$path" in
    ''|/*|.|..|./*|../*|*/./*|*/../*|*/|*'//'*)
      fail "snapshot manifest contains an unsafe payload path: $path"
      ;;
  esac
}

validate_snapshot_payload() {
  local manifest_paths payload_paths line hash path payload relative
  local manifest_count unique_count
  manifest_paths="$(mktemp "${TMPDIR:-/tmp}/deployment-input-manifest-paths.XXXXXX")"
  payload_paths="$(mktemp "${TMPDIR:-/tmp}/deployment-input-payload-paths.XXXXXX")"
  manifest_count=0

  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *"  "* ]] || fail 'snapshot manifest has an invalid entry'
    hash="${line%%  *}"
    path="${line#*  }"
    [[ "$hash" =~ ^[0-9a-fA-F]{64}$ ]] || fail 'snapshot manifest has an invalid SHA-256 value'
    assert_manifest_relative_path "$path"
    payload="$snapshot_dir/inputs/$path"
    [[ -f "$payload" && ! -L "$payload" ]] || fail "snapshot payload is missing a regular file: $path"
    [[ "$(sha256 "$payload")" == "$hash" ]] || fail "snapshot payload hash does not match manifest: $path"
    printf '%s\n' "$path" >> "$manifest_paths"
    manifest_count=$((manifest_count + 1))
  done < "$snapshot_dir/manifest.sha256"
  [[ "$manifest_count" -gt 0 ]] || fail 'snapshot manifest is empty'

  unique_count="$(LC_ALL=C sort -u "$manifest_paths" | wc -l | tr -d '[:space:]')"
  [[ "$unique_count" == "$manifest_count" ]] || fail 'snapshot manifest contains duplicate payload paths'

  while IFS= read -r payload; do
    relative="${payload#"$snapshot_dir/inputs"/}"
    assert_manifest_relative_path "$relative"
    printf '%s\n' "$relative" >> "$payload_paths"
  done < <(find "$snapshot_dir/inputs" -type f -print | LC_ALL=C sort)

  while IFS= read -r relative; do
    grep -Fqx -- "$relative" "$manifest_paths" || fail "snapshot payload has an unlisted file: $relative"
  done < "$payload_paths"
  rm -f "$manifest_paths" "$payload_paths"
}

case "$action" in
  create)
    [[ ! -e "$snapshot_dir" ]] || fail "snapshot target already exists: $snapshot_dir"
    umask 077
    mkdir "$snapshot_dir"
    chmod 700 "$snapshot_dir"
    mkdir "$snapshot_dir/inputs"
    for source in "$env_file" "$config_file" "$secret_file" "$offline_assets"; do
      relative_path="$(snapshot_relative_path "$source")"
      mkdir -p "$snapshot_dir/inputs/$(dirname "$relative_path")"
      cp -pR "$source" "$snapshot_dir/inputs/$relative_path"
    done
    write_manifest "$snapshot_dir/manifest.sha256"
    {
      printf 'created_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'source_revision=%s\n' "$(git -C "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" rev-parse HEAD 2>/dev/null || printf unknown)"
      printf 'environment_file=%s\n' "$(basename "$env_file")"
    } > "$snapshot_dir/metadata.env"
    chmod -R go-rwx "$snapshot_dir"
    chmod 600 "$snapshot_dir/inputs/$(snapshot_relative_path "$secret_file")"
    printf 'created protected deployment-input snapshot: %s\n' "$snapshot_dir"
    ;;
  verify)
    [[ -d "$snapshot_dir" && ! -L "$snapshot_dir" ]] || fail "snapshot must be a non-symlink directory: $snapshot_dir"
    snapshot_mode="$(file_mode "$snapshot_dir")"
    [[ "$snapshot_mode" == 700 ]] || fail "snapshot directory must be mode 700, got $snapshot_mode"
    [[ -f "$snapshot_dir/manifest.sha256" && ! -L "$snapshot_dir/manifest.sha256" ]] || fail "missing snapshot manifest: $snapshot_dir/manifest.sha256"
    [[ -d "$snapshot_dir/inputs" && ! -L "$snapshot_dir/inputs" ]] || fail 'snapshot is missing a regular inputs directory'
    if [[ -n "$(find "$snapshot_dir/inputs" -type l -print -quit)" ]]; then
      fail 'snapshot inputs must not contain symlinks'
    fi
    validate_snapshot_payload
    current_manifest="$(mktemp "${TMPDIR:-/tmp}/deployment-input-manifest.XXXXXX")"
    trap 'rm -f "$current_manifest"' EXIT
    write_manifest "$current_manifest"
    if ! cmp -s "$snapshot_dir/manifest.sha256" "$current_manifest"; then
      printf 'deployment-input-snapshot: current inputs differ from snapshot %s\n' "$snapshot_dir" >&2
      diff -u "$snapshot_dir/manifest.sha256" "$current_manifest" >&2 || true
      exit 1
    fi
    printf 'deployment inputs match snapshot: %s\n' "$snapshot_dir"
    ;;
  restore)
    [[ -d "$snapshot_dir" && ! -L "$snapshot_dir" ]] || fail "snapshot must be a non-symlink directory: $snapshot_dir"
    snapshot_mode="$(file_mode "$snapshot_dir")"
    [[ "$snapshot_mode" == 700 ]] || fail "snapshot directory must be mode 700, got $snapshot_mode"
    [[ -f "$snapshot_dir/manifest.sha256" && ! -L "$snapshot_dir/manifest.sha256" ]] || fail "missing snapshot manifest: $snapshot_dir/manifest.sha256"
    command -v rsync >/dev/null 2>&1 || fail 'restore requires rsync for an exact offline-assets rollback'
    [[ -d "$snapshot_dir/inputs" && ! -L "$snapshot_dir/inputs" ]] || fail 'snapshot is missing a regular inputs directory'
    if [[ -n "$(find "$snapshot_dir/inputs" -type l -print -quit)" ]]; then
      fail 'snapshot inputs must not contain symlinks'
    fi
    validate_snapshot_payload
    snapshot_env="$snapshot_dir/inputs/.env"
    [[ -f "$snapshot_env" && ! -L "$snapshot_env" ]] || fail 'snapshot is missing a regular .env'
    restore_config_file="$(resolve_input_path "$(env_value_from "$snapshot_env" KIOSK_CONFIG_FILE ./config/config.yaml)")"
    restore_secret_file="$(resolve_input_path "$(env_value_from "$snapshot_env" KIOSK_API_KEY_FILE ./secrets/immich_api_key)")"
    for source in "$env_file" "$restore_config_file" "$restore_secret_file"; do
      relative_path="$(snapshot_relative_path "$source")"
      [[ -f "$snapshot_dir/inputs/$relative_path" && ! -L "$snapshot_dir/inputs/$relative_path" ]] || fail "snapshot is missing a regular input: $relative_path"
    done
    [[ -d "$snapshot_dir/inputs/offline-assets" && ! -L "$snapshot_dir/inputs/offline-assets" ]] || fail 'snapshot is missing offline-assets'
    [[ "$(file_mode "$snapshot_dir/inputs/$(snapshot_relative_path "$restore_secret_file")")" == 600 ]] || \
      fail 'snapshot API-key secret must be mode 600'

    # This is intentionally explicit: the confirmation token acknowledges that
    # restoring a known-good snapshot overwrites the current private inputs.
    for source in "$env_file" "$restore_config_file" "$restore_secret_file"; do
      relative_path="$(snapshot_relative_path "$source")"
      mkdir -p "$(dirname "$source")"
      cp -p "$snapshot_dir/inputs/$relative_path" "$source"
    done
    rsync -a --delete "$snapshot_dir/inputs/offline-assets/" "$offline_assets/"
    chmod 600 "$restore_secret_file"
    "$0" verify "$snapshot_dir" "$env_file"
    printf 'restored deployment inputs from snapshot: %s\n' "$snapshot_dir"
    ;;
esac
