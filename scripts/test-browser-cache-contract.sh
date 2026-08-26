#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/browser-cache-contract.XXXXXX")"
trap 'rm -rf "$tmp_root"' EXIT

fail() {
  printf 'browser cache contract failed: %s\n' "$*" >&2
  exit 1
}

[[ -x "$repo_root/scripts/ci-lib.sh" ]] || fail 'missing CI helper'
source "$repo_root/scripts/ci-lib.sh"
ci_prepare_upstream_source "$tmp_root/upstream" >/dev/null

for required_file in \
  frontend/tests/browser-cache-contract.test.mjs \
  frontend/tests/offline-cache-contract.test.mjs; do
  [[ -f "$tmp_root/upstream/$required_file" ]] || \
    fail "Dockerfile patch stack is missing: $required_file"
done

(cd "$tmp_root/upstream" && node --test frontend/tests/browser-cache-contract.test.mjs)
(cd "$tmp_root/upstream" && node --test frontend/tests/offline-cache-contract.test.mjs)
grep -Fq 'Cache-Control", "private, no-cache, no-transform"' "$tmp_root/upstream/main.go" || \
  fail 'media responses must require revalidation'
printf 'browser cache contract passed\n'
