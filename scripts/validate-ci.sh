#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

sh_files=(
  examples/frame-mode-router/frame-mode-router.sh
  examples/home-assistant-wall-panel/build-weather-loops.sh
  scripts/audit-licenses.sh
  scripts/check-offline-assets-permissions.sh
  scripts/ci-lib.sh
  scripts/run-gitleaks.sh
  scripts/run-govulncheck.sh
  scripts/run-trivy.sh
  scripts/test-frame-mode-router.sh
  scripts/test-browser-cache-contract.sh
  scripts/test-offline-assets-permissions.sh
  scripts/test-deployment-input-snapshot.sh
  scripts/test-check-frame-readiness.sh
  scripts/deployment-input-snapshot.sh
  scripts/check-frame-readiness.sh
  scripts/test-license-audit.sh
  scripts/validate-frameos.sh
  scripts/validate-ci.sh
  scripts/validate.sh
)

bash -n "${sh_files[@]}"
shellcheck -x "${sh_files[@]}"

./scripts/test-license-audit.sh

if ! grep -Fxq './scripts/test-check-frame-readiness.sh' scripts/validate.sh; then
  printf 'aggregate validation must execute the frame readiness contract\n' >&2
  exit 1
fi

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
require "json"
require "base64"

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

declared_patches = dockerfile.scan(/^COPY[[:space:]]+([^[:space:]]+\.patch)[[:space:]]/).flatten
required_cache_patch_order = %w[
  backend-cache-regression-tests.patch
  backend-cache-refill-regression-tests.patch
  browser-cache-tests.patch
  offline-cache-tests.patch
  backend-cache-hardening.patch
  backend-cache-refill-hardening.patch
  browser-cache-hardening.patch
  offline-cache-hardening.patch
  offline-mutation-hardening.patch
]
positions = required_cache_patch_order.map { |patch| declared_patches.index(patch) }
declared_once = required_cache_patch_order.all? { |patch| declared_patches.count(patch) == 1 }
unless declared_once && positions.none?(&:nil?) && positions == positions.sort
  warn "Dockerfile cache patches must be declared exactly once in test-before-source dependency order"
  exit 1
end

unless dockerfile.include?("node --test tests/browser-cache-contract.test.mjs tests/offline-cache-contract.test.mjs")
  warn "frontend build must execute the browser/offline cache contracts"
  exit 1
end

unless File.read("scripts/audit-licenses.sh").include?('--user "$(id -u):$(id -g)"')
  warn "Node license audit must not leave root-owned files in the CI workspace"
  exit 1
end

unless File.read("scripts/run-gitleaks.sh").include?("grep -Eq '(^|[^[:digit:]])0 commits scanned([^[:digit:]]|$)'")
  warn "Gitleaks zero-history guard must not reject multi-digit commit counts ending in zero"
  exit 1
end

compose = YAML.load_file("docker-compose.yaml")
kiosk = compose.fetch("services").fetch("immich-kiosk")
unless kiosk.dig("healthcheck", "test") == ["CMD", "/kiosk", "--livecheck"]
  warn "Compose liveness must use the backend-defined /livez CLI probe"
  exit 1
end
unless kiosk["stop_grace_period"] == "30s"
  warn "Compose must reserve a 30-second application shutdown grace period"
  exit 1
end

%w[scripts/deployment-input-snapshot.sh scripts/check-frame-readiness.sh].each do |file|
  unless File.file?(file) && File.executable?(file)
    warn "missing executable deployment resilience tool: #{file}"
    exit 1
  end
end

router_example_files = [
  "examples/frame-mode-router/frame-mode-router.example.conf",
  "examples/frame-mode-router/keymapper-mode-router.example.json"
]
router_example_files.each do |file|
  contents = File.read(file)
  urls = contents.scan(%r{https?://[^\s"']+})
  unless urls.all? { |url| url.match?(%r{\Ahttps?://(?:[A-Za-z0-9-]+\.)*example\.invalid(?:/|\z)}) }
    warn "#{file} may only contain example.invalid URLs"
    exit 1
  end
end

keymapper = JSON.parse(File.read("examples/frame-mode-router/keymapper-mode-router.example.json"))
unless keymapper["keymap_db_version"] == 22 && keymapper["app_version"] == 259
  warn "frame-mode-router Key Mapper export must use database version 22 and app version 259"
  exit 1
end

keymaps = keymapper.fetch("keymap_list")
unless keymaps.is_a?(Array) && keymaps.length == 2 && keymaps.all? { |keymap| keymap["isEnabled"] == false }
  warn "frame-mode-router Key Mapper export must contain exactly two disabled key maps"
  exit 1
end

def values_for_key(node, expected_key)
  case node
  when Hash
    node.each_with_object([]) do |(key, value), values|
      values.concat([value]) if key == expected_key
      values.concat(values_for_key(value, expected_key))
    end
  when Array
    node.flat_map { |value| values_for_key(value, expected_key) }
  else
    []
  end
end

uids = values_for_key(keymapper, "uid")
unless uids.length == 6 && uids.all? { |uid| uid.is_a?(String) && uid.match?(/\A[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\z/i) } &&
       uids.length == uids.uniq.length
  warn "frame-mode-router Key Mapper export must contain exactly six valid unique UUIDs"
  exit 1
end

def contains_scan_code?(node, code)
  case node
  when Hash
    node.any? do |key, value|
      (key == "scanCode" && value.to_i == code) || contains_scan_code?(value, code)
    end
  when Array
    node.any? { |value| contains_scan_code?(value, code) }
  else
    false
  end
end

expected_commands = {
  251 => "sh /data/local/tmp/frame-mode-router.sh next",
  252 => "sh /data/local/tmp/frame-mode-router.sh prev"
}
expected_commands.each do |scan_code, expected_command|
  rule = keymaps.find do |candidate|
    candidate["isEnabled"] == false && contains_scan_code?(candidate, scan_code.to_i)
  end
  action = rule&.fetch("actionList", nil)&.yield_self { |actions| actions.length == 1 ? actions.first : nil }
  trigger_key = rule&.dig("trigger", "keys")&.yield_self { |keys| keys.length == 1 ? keys.first : nil }
  timeout_extras = action ? action.fetch("extras", []).select { |extra| extra["id"] == "extra_shell_command_timeout" } : []
  decoded_command = begin
    Base64.strict_decode64(action.fetch("data"))
  rescue ArgumentError, KeyError, NoMethodError
    nil
  end
  unless trigger_key&.fetch("deviceName", nil).is_a?(String) && !trigger_key["deviceName"].empty? &&
         trigger_key["keyCode"] == 0 && trigger_key["scanCode"] == scan_code &&
         action&.fetch("type", nil) == "SHELL_COMMAND" && action["flags"] == 32 &&
         timeout_extras.length == 1 && timeout_extras.first["data"] == "30000" &&
         decoded_command == expected_command
    warn "Key Mapper gesture #{scan_code} must have the expected deviceName/keyCode shape and exact ADB shell action"
    exit 1
  end
end
RUBY

./scripts/test-offline-assets-permissions.sh

printf 'ci static validation passed\n'
