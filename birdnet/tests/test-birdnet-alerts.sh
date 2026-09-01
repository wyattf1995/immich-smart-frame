#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
AUTOMATION_FILE="$ROOT_DIR/home-assistant/birdnet-phone-alerts.yaml.example"
PROVIDER_FILE="$ROOT_DIR/home-assistant/birdnet-webhook-provider.yaml.example"
OPS_FILE="$ROOT_DIR/OPERATIONS.md"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

[[ -f "$AUTOMATION_FILE" ]] || fail 'Home Assistant BirdNET automation example is missing'
[[ -f "$PROVIDER_FILE" ]] || fail 'BirdNET Home Assistant webhook-provider example is missing'

# The Home Assistant receiver is a local-only, POST-only webhook. Its identifier
# is a bearer secret and must remain an explicit high-entropy placeholder in git.
grep -Fq 'trigger: webhook' "$AUTOMATION_FILE" || fail 'automation must use a webhook trigger'
grep -Fq 'local_only: true' "$AUTOMATION_FILE" || fail 'automation webhook must be LAN-only'
grep -Fq 'allowed_methods:' "$AUTOMATION_FILE" || fail 'automation must constrain webhook methods'
grep -Fq -- '- POST' "$AUTOMATION_FILE" || fail 'automation must accept POST'
grep -Fq 'webhook_id: REPLACE_WITH_RANDOM_WEBHOOK_ID' "$AUTOMATION_FILE" || \
  fail 'automation must retain the random webhook placeholder'
grep -Fq 'action: notify.mobile_app_REPLACE_ME' "$AUTOMATION_FILE" || \
  fail 'automation must retain an explicit mobile target placeholder'
grep -Fq 'trigger.json.title' "$AUTOMATION_FILE" || fail 'phone title must come from the bounded webhook payload'
grep -Fq 'trigger.json.message' "$AUTOMATION_FILE" || fail 'phone message must come from the bounded webhook payload'

# BirdNET sends only high-priority detection notifications. The custom payload
# intentionally omits coordinates, internal detection URLs, and raw metadata.
grep -Fq 'type: webhook' "$PROVIDER_FILE" || fail 'provider example must use BirdNET webhook delivery'
grep -Fq 'enabled: true' "$PROVIDER_FILE" || fail 'provider example must be explicitly enabled'
grep -Fq 'http://HOME_ASSISTANT_LAN_HOST/api/webhook/REPLACE_WITH_RANDOM_WEBHOOK_ID' "$PROVIDER_FILE" || \
  fail 'provider must use the matching private Home Assistant webhook placeholder'
grep -Eq 'types:[[:space:]]*\[detection\]' "$PROVIDER_FILE" || \
  fail 'provider must filter to detection notifications'
grep -Eq 'priorities:[[:space:]]*\[high,[[:space:]]*critical\]' "$PROVIDER_FILE" || \
  fail 'provider must filter to high and critical notifications'
for payload_field in Type Priority Title Message timestamp; do
  grep -Fq "{{.${payload_field}}}" "$PROVIDER_FILE" || fail "bounded payload is missing ${payload_field}"
done
! grep -Eiq 'MetadataJSON|bg_latitude|bg_longitude|detection_url|image_url' "$PROVIDER_FILE" || \
  fail 'phone webhook payload must not include location, internal URLs, or raw metadata'
! grep -Eiq 'bearer|token(_file)?|password|authorization' "$PROVIDER_FILE" || \
  fail 'Home Assistant webhook provider must not receive an HA access token'

! grep -Eq '[[:xdigit:]]{32,}' "$AUTOMATION_FILE" "$PROVIDER_FILE" || \
  fail 'tracked alert examples must not contain a real high-entropy webhook ID'

grep -Eiq 'new species' "$OPS_FILE" || fail 'operations must document new-species phone alerts'
grep -Eiq 'infrequent species' "$OPS_FILE" || fail 'operations must document infrequent-species phone alerts'
grep -Eiq 'high-confidence|high confidence' "$OPS_FILE" || fail 'operations must document high-confidence phone alerts'
grep -Eq '95%' "$OPS_FILE" || fail 'operations must state the high-confidence threshold'
grep -Eiq '15[- ]minute|900[- ]second' "$OPS_FILE" || fail 'operations must document high-confidence cooldown'
grep -Eiq 'test notification|test alert' "$OPS_FILE" || fail 'operations must document an end-to-end phone alert test'
grep -Eiq 'alert.*rollback|rollback.*alert' "$OPS_FILE" || fail 'operations must document phone-alert rollback'

printf 'PASS: BirdNET Home Assistant alert contract\n'
