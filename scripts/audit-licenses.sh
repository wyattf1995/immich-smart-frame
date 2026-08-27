#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci-lib.sh
source "$script_dir/ci-lib.sh"

temp_root=""
shared_source_dir="${PATCHED_UPSTREAM_DIR:-}"
if [[ -n "$shared_source_dir" ]]; then
  source_dir="$shared_source_dir"
  report_dir="$(mktemp -d)"
  trap 'rm -rf "$report_dir"' EXIT
else
  temp_root="$(mktemp -d)"
  trap 'rm -rf "$temp_root"' EXIT
  source_dir="$temp_root/upstream"
  report_dir="$temp_root/reports"
fi
mkdir -p "$report_dir"

if [[ -z "$shared_source_dir" ]]; then
  ci_prepare_upstream_source "$source_dir" >/dev/null
fi

docker run --rm \
  --entrypoint /bin/bash \
  -v "$source_dir:/src" \
  -v "$report_dir:/reports" \
  -w /src \
  "golang:$(ci_go_image)" \
  -lc '
    set -euo pipefail
    export PATH="/usr/local/go/bin:$PATH"
    go install github.com/google/go-licenses@v1.6.0
    export PATH="$(go env GOPATH)/bin:$PATH"
    go mod download
    go tool templ generate
    go-licenses report ./... > /reports/go-licenses.csv
    go-licenses check ./... \
      --ignore github.com/golang/freetype \
      --allowed_licenses=0BSD,AGPL-3.0,Apache-2.0,BSD-2-Clause,BSD-3-Clause,ISC,MIT,MPL-2.0
  '

docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/npm-home \
  --entrypoint /bin/sh \
  -v "$source_dir:/src" \
  -v "$report_dir:/reports" \
  -w /src/frontend \
  "node:$(ci_node_image)" \
  -lc '
    set -eu
    npm ci --ignore-scripts --no-audit --no-fund
    npx --yes license-checker-rseidelsohn@5.0.1 --json > /reports/node-licenses.json
  '

ruby - "$report_dir/node-licenses.json" <<'RUBY'
require "json"

allowed = %w[
  0bsd
  agpl-3.0-only
  apache-2.0
  bsd-2-clause
  bsd-3-clause
  cc0-1.0
  isc
  mit
  mpl-2.0
  public-domain
  python-2.0
  unlicense
].freeze
connectors = %w[and or with].freeze

licenses = JSON.parse(File.read(ARGV[0]))
violations = []
summary = Hash.new { |hash, key| hash[key] = [] }

licenses.each do |package_name, payload|
  raw = payload["licenses"]
  values = Array(raw).map(&:to_s)
  values = [raw.to_s] if values.empty?

  values.each do |value|
    normalized = value.downcase.gsub(/[()]/, " ")
    tokens = normalized.scan(/[a-z0-9.+-]+/)
    unknown = tokens.reject { |token| allowed.include?(token) || connectors.include?(token) }

    summary[value] << package_name
    next if unknown.empty?

    violations << "#{package_name}: #{value}"
  end
end

summary.keys.sort.each do |license|
  puts "node #{license}: #{summary[license].count} package(s)"
end

unless violations.empty?
  warn "node dependency licenses outside allowlist:"
  violations.sort.each { |line| warn line }
  exit 1
end
RUBY

printf 'go manual override: github.com/golang/freetype => FTL OR GPL-2.0-or-later\n'
printf 'license audit passed\n'
