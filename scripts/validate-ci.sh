#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

sh_files=(
  scripts/audit-licenses.sh
  scripts/ci-lib.sh
  scripts/run-gitleaks.sh
  scripts/run-govulncheck.sh
  scripts/run-trivy.sh
  scripts/validate-ci.sh
  scripts/validate.sh
)

bash -n "${sh_files[@]}"
shellcheck -x "${sh_files[@]}"

# shellcheck source=scripts/ci-lib.sh
source scripts/ci-lib.sh
if [[ ! "$(ci_upstream_ref)" =~ ^[0-9a-f]{40}$ ]]; then
  printf 'CI audits must fetch the exact KIOSK_UPSTREAM_COMMIT\n' >&2
  exit 1
fi

expected_project_version=0.1.0
declared_versions=(
  "$(sed -n 's/^KIOSK_VERSION=//p' .env.example)"
  "$(sed -n 's/^ARG KIOSK_VERSION=//p' custom-image/Dockerfile)"
  "$(sed -n 's/.*KIOSK_VERSION:-\([^}]*\).*/\1/p' docker-compose.yaml)"
)
for declared_version in "${declared_versions[@]}"; do
  if [[ "$declared_version" != "$expected_project_version" ]]; then
    printf 'project version mismatch: expected %s, found %s\n' \
      "$expected_project_version" "$declared_version" >&2
    exit 1
  fi
done

ruby <<'RUBY'
require "yaml"

def load_trusted_yaml(file)
  YAML.load_file(file)
end

workflow_files = Dir[".github/workflows/*.yml"].sort
files = workflow_files + [".github/dependabot.yml"]
files.each do |file|
  load_trusted_yaml(file)
end

dependabot = load_trusted_yaml(".github/dependabot.yml")
updates = dependabot.fetch("updates")
ecosystems = updates.map { |entry| entry.fetch("package-ecosystem") }
%w[docker github-actions].each do |ecosystem|
  next if ecosystems.include?(ecosystem)

  warn ".github/dependabot.yml is missing #{ecosystem}"
  exit 1
end

def each_uses(node, &block)
  case node
  when Hash
    node.each do |key, value|
      yield value if key == "uses"
      each_uses(value, &block)
    end
  when Array
    node.each { |value| each_uses(value, &block) }
  end
end

workflow_files.each do |file|
  workflow = load_trusted_yaml(file)
  permissions = workflow.fetch("permissions")
  workflow_on = workflow["on"] || workflow[true]
  unless permissions.is_a?(Hash)
    warn "#{file} must declare explicit permissions"
    exit 1
  end

  if file.end_with?("/release.yml")
    unless permissions == { "contents" => "read", "packages" => "write" }
      warn "#{file} must keep only contents:read and packages:write"
      exit 1
    end

    push = workflow_on.fetch("push")
    unless workflow_on.keys == ["push"] && push["tags"] == ["v*"]
      warn "#{file} must be tag-only on v*"
      exit 1
    end
  elsif !permissions.values.all? { |value| value == "read" }
    warn "#{file} must keep read-only permissions"
    exit 1
  end

  each_uses(workflow) do |uses_value|
    next if uses_value.start_with?("./", "docker://")
    next if uses_value.match?(/@[0-9a-f]{40}\z/)

    warn "#{file} has an unpinned action reference: #{uses_value}"
    exit 1
  end
end

{
  "scripts/run-trivy.sh" => /aquasec\/trivy:0\.74\.0@sha256:[0-9a-f]{64}/,
  "scripts/run-gitleaks.sh" => /zricethezav\/gitleaks:v8\.30\.1@sha256:[0-9a-f]{64}/
}.each do |file, pattern|
  next if File.read(file).match?(pattern)

  warn "#{file} must pin its scanner container by digest"
  exit 1
end

dockerfile = File.read("custom-image/Dockerfile")
from_lines = dockerfile.lines.grep(/^FROM /)
unless from_lines.length == 4 && from_lines.all? { |line| line.match?(/@sha256:[0-9a-f]{64}(?:\s|$)/) }
  warn "every Dockerfile base image must be pinned by sha256 digest"
  exit 1
end

unless dockerfile.match?(/^ARG GO_TASK_VERSION=\d+\.\d+\.\d+$/)
  warn "GO_TASK_VERSION must remain exactly pinned"
  exit 1
end
RUBY

printf 'ci static validation passed\n'
