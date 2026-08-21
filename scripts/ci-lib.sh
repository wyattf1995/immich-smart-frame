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
  local upstream_commit
  upstream_commit="$(sed -n 's/^ARG KIOSK_UPSTREAM_COMMIT=//p' "$(ci_dockerfile)")"

  if [[ "$upstream_commit" =~ ^[0-9a-f]{40}$ ]]; then
    printf '%s\n' "$upstream_commit"
    return
  fi

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

ci_patch_files() {
  local repo_root
  repo_root="$(ci_repo_root)"

  awk '/^COPY[[:space:]]+[^[:space:]]+\.patch[[:space:]]/ { print $2 }' "$(ci_dockerfile)" | while IFS= read -r patch_name; do
    printf '%s/custom-image/%s\n' "$repo_root" "$patch_name"
  done
}

ci_prepare_upstream_source() {
  if [[ $# -ne 1 ]]; then
    printf 'usage: ci_prepare_upstream_source <target-dir>\n' >&2
    return 2
  fi

  local target_dir="$1"
  local repo_root
  local upstream_ref
  local patch_count
  repo_root="$(ci_repo_root)"
  upstream_ref="$(ci_upstream_ref)"

  if [[ -z "$upstream_ref" ]]; then
    printf 'missing KIOSK_UPSTREAM_REF in %s\n' "$(ci_dockerfile)" >&2
    return 1
  fi

  if [[ -e "$target_dir" ]]; then
    if [[ ! -d "$target_dir" ]]; then
      printf 'target path is not a directory: %s\n' "$target_dir" >&2
      return 1
    fi
    if [[ -n "$(find "$target_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
      printf 'target directory must be empty: %s\n' "$target_dir" >&2
      return 1
    fi
  else
    mkdir -p "$(dirname "$target_dir")"
  fi

  git -C "$(dirname "$target_dir")" init --quiet "$target_dir"
  git -C "$target_dir" remote add origin https://github.com/damongolding/immich-kiosk.git
  git -C "$target_dir" fetch --depth 1 origin "$upstream_ref"
  git -C "$target_dir" checkout --quiet --detach FETCH_HEAD

  local patch_file
  patch_count=0
  while IFS= read -r patch_file; do
    if [[ ! -f "$patch_file" ]]; then
      printf 'missing Dockerfile patch: %s\n' "$patch_file" >&2
      return 1
    fi
    git -C "$target_dir" apply --check --unidiff-zero "$patch_file"
    git -C "$target_dir" apply --unidiff-zero "$patch_file"
    patch_count=$((patch_count + 1))
  done < <(ci_patch_files)

  if [[ "$patch_count" -eq 0 ]]; then
    printf 'no local .patch files declared in %s\n' "$(ci_dockerfile)" >&2
    return 1
  fi
}
