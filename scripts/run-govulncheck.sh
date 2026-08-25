#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci-lib.sh
source "$script_dir/ci-lib.sh"

if [[ -n "${PATCHED_UPSTREAM_DIR:-}" ]]; then
  source_dir="$PATCHED_UPSTREAM_DIR"
else
  temp_root="$(mktemp -d)"
  trap 'rm -rf "$temp_root"' EXIT
  source_dir="$temp_root/upstream"
  ci_prepare_upstream_source "$source_dir" >/dev/null
fi

docker run --rm \
  --entrypoint /bin/bash \
  -v "$source_dir:/src" \
  -w /src \
  "golang:$(ci_go_image)" \
  -lc '
    set -euo pipefail
    export PATH="/usr/local/go/bin:$PATH"
    go install golang.org/x/vuln/cmd/govulncheck@v1.1.4
    export PATH="$(go env GOPATH)/bin:$PATH"
    go mod download
    go tool templ generate
    govulncheck ./...
  '
