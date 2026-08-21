#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
git_common_dir="$(git -C "$repo_root" rev-parse --path-format=absolute --git-common-dir)"
git_dir="$(git -C "$repo_root" rev-parse --path-format=absolute --absolute-git-dir)"
log_file="$(mktemp)"
trap 'rm -f "$log_file"' EXIT

docker_args=(
  --rm
  -v "$repo_root:$repo_root"
  -v "$git_common_dir:$git_common_dir"
)

if [[ "$git_dir" != "$git_common_dir" ]]; then
  docker_args+=(-v "$git_dir:$git_dir")
fi

docker run "${docker_args[@]}" \
  zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
  git "$repo_root" --log-opts="--all" --redact --verbose 2>&1 | tee "$log_file"

if grep -q '0 commits scanned' "$log_file"; then
  printf 'gitleaks did not see repository history\n' >&2
  exit 1
fi

printf 'gitleaks history scan passed\n'
