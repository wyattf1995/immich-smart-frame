#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/browser-cache-contract.XXXXXX")"

fail() {
  printf 'browser cache contract failed: %s\n' "$*" >&2
  exit 1
}

[[ -x "$repo_root/scripts/ci-lib.sh" ]] || fail 'missing CI helper'
source "$repo_root/scripts/ci-lib.sh"
ci_prepare_upstream_source "$tmp_root/upstream" >/dev/null

for patch_file in \
  "$repo_root/custom-image/browser-cache-hardening.patch" \
  "$repo_root/custom-image/offline-cache-hardening.patch" \
  "$repo_root/custom-image/offline-mutation-hardening.patch" \
  "$repo_root/custom-image/offline-cache-tests.patch" \
  "$repo_root/custom-image/browser-cache-tests.patch"; do
  [[ -f "$patch_file" ]] || fail "missing browser cache patch: $patch_file"
  git -C "$tmp_root/upstream" apply --check --unidiff-zero "$patch_file" || fail "patch does not apply: $patch_file"
  git -C "$tmp_root/upstream" apply --unidiff-zero "$patch_file"
done

(cd "$tmp_root/upstream" && node --test frontend/tests/browser-cache-contract.test.mjs)
(cd "$tmp_root/upstream" && node --test frontend/tests/offline-cache-contract.test.mjs)
grep -Fq 'Cache-Control", "private, no-cache, no-transform"' "$tmp_root/upstream/main.go" || \
  fail 'media responses must require revalidation'
printf 'browser cache contract passed\n'
