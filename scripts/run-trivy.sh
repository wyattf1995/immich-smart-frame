#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

image_ref="${KIOSK_IMAGE:-local/immich-kiosk:0.42.0-lenovo-curation1}"
trivy_image="aquasec/trivy:0.66.0"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  "$trivy_image" \
  image --scanners vuln --severity HIGH,CRITICAL "$image_ref"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  "$trivy_image" \
  image --scanners vuln --severity CRITICAL --exit-code 1 "$image_ref"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  "$trivy_image" \
  image --scanners secret --exit-code 1 "$image_ref"

printf 'trivy image vulnerability and secret scans passed\n'
