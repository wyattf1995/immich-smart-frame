#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

docker() {
  local argument
  local report_dir=""

  for argument in "$@"; do
    case "$argument" in
      *:/reports)
        report_dir="${argument%:/reports}"
        ;;
    esac
  done

  if [[ -n "$report_dir" ]]; then
    case "${LICENSE_AUDIT_FIXTURE:?missing license audit fixture}" in
      allowed)
        printf '%s\n' '{"immich-kiosk@0.39.0":{"licenses":"AGPL-3.0-only"}}' \
          > "$report_dir/node-licenses.json"
        ;;
      denied)
        printf '%s\n' \
          '{"immich-kiosk@0.39.0":{"licenses":"AGPL-3.0-only"},"denied-package@1.0.0":{"licenses":"GPL-3.0-only"}}' \
          > "$report_dir/node-licenses.json"
        ;;
      *)
        printf 'unknown license audit fixture: %s\n' "$LICENSE_AUDIT_FIXTURE" >&2
        return 2
        ;;
    esac
  fi
}
export -f docker

failed=false

if ! allowed_output="$(PATCHED_UPSTREAM_DIR="$repo_root" LICENSE_AUDIT_FIXTURE=allowed scripts/audit-licenses.sh 2>&1)"; then
  printf 'license audit rejected the allowed project license:\n%s\n' "$allowed_output" >&2
  failed=true
elif ! grep -Fq 'node AGPL-3.0-only: 1 package(s)' <<< "$allowed_output"; then
  printf 'license audit did not execute its Node validator for the project license:\n%s\n' \
    "$allowed_output" >&2
  failed=true
fi

if denied_output="$(PATCHED_UPSTREAM_DIR="$repo_root" LICENSE_AUDIT_FIXTURE=denied scripts/audit-licenses.sh 2>&1)"; then
  printf 'license audit accepted a dependency outside the allowlist:\n%s\n' "$denied_output" >&2
  failed=true
elif ! grep -Fq 'denied-package@1.0.0: GPL-3.0-only' <<< "$denied_output"; then
  printf 'license audit rejected the denied fixture without identifying it:\n%s\n' \
    "$denied_output" >&2
  failed=true
fi

if [[ "$failed" == true ]]; then
  exit 1
fi

printf 'license audit contract passed\n'
