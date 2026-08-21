#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

static_only=false
if [[ "${1:-}" == "--static" ]]; then
  static_only=true
elif [[ $# -gt 0 ]]; then
  printf 'usage: %s [--static]\n' "$0" >&2
  exit 2
fi

required_files=(
  .env.example
  LICENSE
  README.md
  SECURITY.md
  THIRD_PARTY_NOTICES.md
  config/config.example.yaml
  config/qwen.example.yaml
  custom-image/fully-kiosk-dpr.patch
  custom-image/weighted-curation.patch
  custom-image/weighted-curation-tests.patch
  docker-compose.yaml
)

for required_file in "${required_files[@]}"; do
  if [[ ! -f "$required_file" ]]; then
    printf 'missing required file: %s\n' "$required_file" >&2
    exit 1
  fi
done

while IFS= read -r tracked_file; do
  case "$tracked_file" in
    .env|config/config.yaml|secrets/*|screenshots/*)
      printf 'private deployment artifact is tracked: %s\n' "$tracked_file" >&2
      exit 1
      ;;
  esac
done < <(git ls-files)

# Bracketed characters keep this pattern from matching its own source line.
private_pattern='192[.]168[.]|/Us[e]rs/|BEGIN (RSA |OPENSSH |EC )?PRIVATE[ ]KEY|[[:alnum:]_.+-]+@gmail[.]com'
if git grep -n -E "$private_pattern" -- . ':!LICENSE'; then
  printf 'possible private deployment value found in tracked files\n' >&2
  exit 1
fi

expected_license_blob=0ad25db4bd1d86c452db3f9602ccdbe172438f52
actual_license_blob="$(git hash-object LICENSE)"
if [[ "$actual_license_blob" != "$expected_license_blob" ]]; then
  printf 'LICENSE does not match Immich Kiosk v0.42.0 AGPL-3.0 text\n' >&2
  exit 1
fi

if command -v ruby >/dev/null 2>&1; then
  ruby scripts/validate-config.rb config/config.example.yaml config/qwen.example.yaml
else
  docker run --rm -v "$repo_root:/work:ro" -w /work ruby:3.4-alpine \
    ruby scripts/validate-config.rb config/config.example.yaml config/qwen.example.yaml
fi

docker compose --env-file .env.example config --quiet

if [[ "$static_only" == true ]]; then
  printf 'static validation passed\n'
  exit 0
fi

docker compose --env-file .env.example build immich-kiosk
printf 'full validation passed\n'
