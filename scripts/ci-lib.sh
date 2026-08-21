#!/usr/bin/env bash
set -euo pipefail

ci_repo_root() {
  local script_source="${BASH_SOURCE[0]:-$0}"
  cd "$(dirname "$script_source")/.." && pwd
}

ci_dockerfile() {
  printf '%s/custom-image/Dockerfile\n' "$(ci_repo_root)"
}

ci_upstream_ref() {
  sed -n 's/^ARG KIOSK_UPSTREAM_REF=//p' "$(ci_dockerfile)"
}

ci_go_image() {
  sed -n 's/^FROM golang:\([^[:space:]]*\) AS build$/\1/p' "$(ci_dockerfile)"
}

ci_node_image() {
  sed -n 's/^FROM node:\([^[:space:]]*\) AS frontend-build$/\1/p' "$(ci_dockerfile)"
}

ci_go_version() {
  ci_go_image | sed 's/-.*//'
}

ci_prepare_upstream_source() {
  if [[ $# -ne 1 ]]; then
    printf 'usage: ci_prepare_upstream_source <target-dir>\n' >&2
    return 2
  fi

  local target_dir="$1"
  local repo_root
  repo_root="$(ci_repo_root)"

  rm -rf "$target_dir"
  git clone --depth 1 --branch "$(ci_upstream_ref)" \
    https://github.com/damongolding/immich-kiosk.git "$target_dir"

  local patch_file
  for patch_file in \
    "$repo_root/custom-image/fully-kiosk-dpr.patch" \
    "$repo_root/custom-image/weighted-curation.patch" \
    "$repo_root/custom-image/weighted-curation-tests.patch"
  do
    git -C "$target_dir" apply --check "$patch_file"
    git -C "$target_dir" apply "$patch_file"
  done
}
