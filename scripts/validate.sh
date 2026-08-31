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
  birdnet/.env.example
  birdnet/config/config.yaml
  birdnet/docker-compose.yaml
  birdnet/OPERATIONS.md
  birdnet/tests/test-birdnet-compose.sh
  LICENSE
  README.md
  SECURITY.md
  THIRD_PARTY_NOTICES.md
  config/config.example.yaml
  config/qwen.example.yaml
  custom-image/album-penalties.patch
  custom-image/album-name-fresh-slide-tests.patch
  custom-image/album-name-fresh-slide.patch
  custom-image/album-date-metadata-tests.patch
  custom-image/album-date-metadata.patch
  custom-image/backend-cache-hardening.patch
  custom-image/backend-cache-refill-hardening.patch
  custom-image/backend-cache-refill-regression-tests.patch
  custom-image/backend-cache-regression-tests.patch
  custom-image/browser-cache-hardening.patch
  custom-image/browser-cache-tests.patch
  custom-image/cancellation-propagation.patch
  custom-image/cancellation-propagation-tests.patch
  custom-image/date-pool-hardening.patch
  custom-image/date-pool-hardening-tests.patch
  custom-image/fully-kiosk-dpr.patch
  custom-image/offline-cache-hardening.patch
  custom-image/offline-cache-tests.patch
  custom-image/offline-mutation-hardening.patch
  custom-image/weighted-curation.patch
  custom-image/weighted-curation-tests.patch
  docker-compose.yaml
  docs/frame-mode-router.md
  examples/frame-mode-router/frame-mode-router.example.conf
  examples/frame-mode-router/frame-mode-router.sh
  examples/frame-mode-router/keymapper-mode-router.example.json
  scripts/audit-licenses.sh
  scripts/check-offline-assets-permissions.sh
  scripts/ci-lib.sh
  scripts/run-gitleaks.sh
  scripts/run-govulncheck.sh
  scripts/run-trivy.sh
  scripts/test-frame-mode-router.sh
  scripts/test-check-frame-readiness.sh
  scripts/test-browser-cache-contract.sh
  scripts/test-offline-assets-permissions.sh
  scripts/validate-frameos.sh
  scripts/validate-frameos-control-receiver.rb
  scripts/validate-frameos-camera-disposal.rb
  scripts/validate-frameos-oauth-callback.rb
  scripts/validate-frameos-panel.rb
  scripts/validate-frameos-surface-layering.rb
  scripts/validate-home-assistant-examples.rb
  scripts/validate-ci.sh
)

for required_file in "${required_files[@]}"; do
  if [[ ! -f "$required_file" ]]; then
    printf 'missing required file: %s\n' "$required_file" >&2
    exit 1
  fi
done

while IFS= read -r tracked_file; do
  case "$tracked_file" in
    .env|config/config.yaml|secrets/*|screenshots/*|offline-assets/*)
      printf 'private deployment artifact is tracked: %s\n' "$tracked_file" >&2
      exit 1
      ;;
  esac
done < <(git ls-files)

if ! grep -Fxq 'offline-assets/' .gitignore; then
  printf 'offline-assets/ must remain ignored because it can contain private photos\n' >&2
  exit 1
fi

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
  ruby scripts/validate-home-assistant-examples.rb
else
  docker run --rm -v "$repo_root:/work:ro" -w /work ruby:3.4-alpine \
    ruby scripts/validate-config.rb config/config.example.yaml config/qwen.example.yaml
  docker run --rm -v "$repo_root:/work:ro" -w /work ruby:3.4-alpine \
    ruby scripts/validate-home-assistant-examples.rb
fi

./scripts/validate-ci.sh

./scripts/test-frame-mode-router.sh

./birdnet/tests/test-birdnet-compose.sh

./scripts/test-check-frame-readiness.sh

./scripts/validate-frameos.sh

ruby scripts/validate-frameos-control-receiver.rb
ruby scripts/validate-frameos-camera-disposal.rb
ruby scripts/validate-frameos-oauth-callback.rb
ruby scripts/validate-frameos-panel.rb
ruby scripts/validate-frameos-surface-layering.rb

docker compose --env-file .env.example config --quiet

if [[ "$static_only" == true ]]; then
  printf 'static validation passed\n'
  exit 0
fi

docker compose --env-file .env.example build immich-kiosk
printf 'full validation passed\n'
