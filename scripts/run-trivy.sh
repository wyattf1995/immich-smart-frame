#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

image_ref="${KIOSK_IMAGE:-local/immich-kiosk:0.42.0-lenovo-curation1}"
trivy_image="aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  "$trivy_image" \
  image --scanners vuln --severity HIGH,CRITICAL --exit-code 1 "$image_ref"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  "$trivy_image" \
  image --scanners secret --exit-code 1 "$image_ref"

printf 'trivy image vulnerability and secret scans passed\n'
