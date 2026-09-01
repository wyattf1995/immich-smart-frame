#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/docker-compose.yaml"
ENV_FILE="$ROOT_DIR/.env.example"
VIEW_FILE="$ROOT_DIR/frame-view/index.html"
NGINX_FILE="$ROOT_DIR/frame-view/nginx.conf"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

for required_file in "$COMPOSE_FILE" "$ENV_FILE" "$VIEW_FILE" "$NGINX_FILE"; do
  [[ -f "$required_file" ]] || fail "missing required frame-view file: $required_file"
done

# The frame view is a separate least-privilege service. Keep the analyzer image
# immutable and proxy only its public read endpoints on one same-origin page.
grep -Fq 'birdnet-frame-view:' "$COMPOSE_FILE" || fail 'Compose must define the frame-view service'
grep -Eq 'ghcr[.]io/nginx/nginx-unprivileged:1[.]31[.]3-alpine3[.]24@sha256:[0-9a-f]{64}' "$COMPOSE_FILE" || \
  fail 'frame-view image must pin the reviewed NGINX release manifest'
grep -Fq 'FRAME_VIEW_PORT:-8091' "$COMPOSE_FILE" || fail 'frame-view must use the reserved LAN port'
grep -Fq '${BIRDNET_FRAME_VIEW_DIR:-/mnt/user/appdata/birdnet-go/frame-view}/index.html:/usr/share/nginx/html/index.html:ro' "$COMPOSE_FILE" || \
  fail 'frame view HTML must come from readable appdata and be mounted read-only'
grep -Fq '${BIRDNET_FRAME_VIEW_DIR:-/mnt/user/appdata/birdnet-go/frame-view}/nginx.conf:/etc/nginx/conf.d/default.conf:ro' "$COMPOSE_FILE" || \
  fail 'frame view proxy configuration must come from readable appdata and be mounted read-only'
grep -Fq 'read_only: true' "$COMPOSE_FILE" || fail 'frame-view root filesystem must be read-only'
grep -Fq 'no-new-privileges:true' "$COMPOSE_FILE" || fail 'frame-view must set no-new-privileges'
grep -Fq 'cap_drop:' "$COMPOSE_FILE" || fail 'frame-view must explicitly drop Linux capabilities'
grep -Fq 'FRAME_VIEW_PORT=8091' "$ENV_FILE" || fail 'sample env must reserve the frame-view port'
grep -Fq 'BIRDNET_FRAME_VIEW_DIR=/mnt/user/appdata/birdnet-go/frame-view' "$ENV_FILE" || \
  fail 'sample env must keep runtime frame assets off the FAT boot filesystem'

# Relative upstream URLs keep browser traffic same-origin and avoid embedding
# credentials, private addresses, or deployment-specific hostnames in the page.
grep -Fq 'proxy_pass http://birdnet-go:8080;' "$NGINX_FILE" || fail 'NGINX must proxy to BirdNET-Go by service name'
grep -Fq 'location /api/' "$NGINX_FILE" || fail 'NGINX must expose the BirdNET public API path'
grep -Fq 'proxy_buffering off;' "$NGINX_FILE" || fail 'SSE proxying must disable response buffering'
grep -Fq 'proxy_set_header Authorization "";' "$NGINX_FILE" || fail 'frame proxy must strip authorization credentials'
grep -Fq 'proxy_set_header Cookie "";' "$NGINX_FILE" || fail 'frame proxy must strip browser credentials'
grep -Fq 'location = /healthz' "$NGINX_FILE" || fail 'frame-view must provide an isolated health endpoint'
grep -Fq "default-src 'none'" "$NGINX_FILE" || fail 'frame-view must send a restrictive content security policy'
! grep -Eq '192[.]168[.]|token=|password=|username=' "$VIEW_FILE" || \
  fail 'frame view must not contain private hosts or URL credentials'

# The display contract intentionally uses BirdNET-Go's public v2 API. Keep the
# surface bounded: one daily summary, recent detections, two SSE streams, and
# on-demand attributed images for the visible species only.
grep -Fq '/api/v2/analytics/species/daily' "$VIEW_FILE" || fail 'frame view must load today species summary'
grep -Fq '/api/v2/detections/recent' "$VIEW_FILE" || fail 'frame view must load recent detections'
grep -Fq '/api/v2/detections/stream' "$VIEW_FILE" || fail 'frame view must refresh on live detections'
grep -Fq 'addEventListener("detection"' "$VIEW_FILE" || fail 'frame view must consume BirdNET named detection events'
grep -Fq '/api/v2/streams/sources' "$VIEW_FILE" || fail 'frame view must inspect public audio source state'
grep -Fq '/api/v2/streams/audio-level' "$VIEW_FILE" || fail 'frame view must distinguish active and missing audio'
grep -Fq '/api/v2/media/species-image?name=' "$VIEW_FILE" || fail 'frame view must show real species images'
grep -Fq '/api/v2/media/species-image/info?name=' "$VIEW_FILE" || fail 'hero image must expose provider attribution'

for state_copy in 'Listening' 'Waiting for a microphone' 'No birds heard yet' 'Bird detector unavailable'; do
  grep -Fq "$state_copy" "$VIEW_FILE" || fail "missing explicit kiosk state: $state_copy"
done

grep -Fq 'aria-live="polite"' "$VIEW_FILE" || fail 'status changes must be announced accessibly'
grep -Fq 'visibilitychange' "$VIEW_FILE" || fail 'hidden pages must release live streams'
grep -Fq 'overflow: hidden' "$VIEW_FILE" || fail 'kiosk view must not expose a scrollbar'
grep -Fq '@media (max-height: 1100px)' "$VIEW_FILE" || fail 'layout must explicitly fit the 1920x1080 frame'
grep -Fq '@media (max-width: 1100px) and (max-height: 650px)' "$VIEW_FILE" || \
  fail 'layout must account for the frame 320-dpi CSS viewport'
grep -Fq 'Photo unavailable' "$VIEW_FILE" || fail 'broken images must retain a stable fallback'
grep -Fq 'retrySpeciesImage' "$VIEW_FILE" || fail 'cold species images must be retried in place'

# Species reference art is displayed inside a fixed hero panel. Preserve the
# complete source image so birds are not cut off by the panel's aspect ratio.
hero_image_css=$(sed -n '/^[[:space:]]*\.hero-image[[:space:]]*{/,/^[[:space:]]*}/p' "$VIEW_FILE")
grep -Fq 'object-fit: contain;' <<<"$hero_image_css" || \
  fail 'hero species image must preserve the complete image with object-fit: contain'

# attachImage hides images until onload. Thumbnails are visible immediately,
# so createThumb must request eager loading rather than relying on a hidden
# image's lazy-load visibility heuristics.
create_thumb_fn=$(sed -n '/^[[:space:]]*function createThumb(/,/^[[:space:]]*function /p' "$VIEW_FILE")
grep -Eq 'attachImage\(img, fallback, scientificName, .*\, true\);' <<<"$create_thumb_fn" || \
  fail 'createThumb must request eager species-image loading'

# Ambient dashboard contract: the kiosk rotates through bounded time windows,
# but a fresh detection must immediately bring the live visitor back. Keep the
# period names in the page source so this behavior remains reviewable without
# depending on a browser or a live BirdNET installation.
grep -Fq 'ROTATION_PERIODS' "$VIEW_FILE" || fail 'frame view must define the automatic rotation periods'
for rotation_period in 'live' 'today' '7[-_ ]?days?' '30[-_ ]?days?'; do
  grep -Eiq "$rotation_period" "$VIEW_FILE" || fail "rotation must expose the $rotation_period period"
done
grep -Fq 'ROTATION_INTERVAL_MS' "$VIEW_FILE" || fail 'frame view must rotate periods on a timer'
grep -Fq 'setRotationPeriod("live")' "$VIEW_FILE" || \
  fail 'a new detection must return the rotating view to Live'

# A species can be interesting even when it is not the newest detection. The
# four novelty reasons are intentionally visible labels, not only styling or
# an inaccessible tooltip.
grep -Fq 'novelty-badge' "$VIEW_FILE" || fail 'species cards must provide an accessible novelty badge'
for novelty_reason in 'lifetime' 'year' 'season' 'infrequent'; do
  grep -Eiq "novelty[^[:cntrl:]]*${novelty_reason}|${novelty_reason}[^[:cntrl:]]*novelty" "$VIEW_FILE" || \
    fail "novelty badges must support the ${novelty_reason} reason"
done

# Detection details are deliberately user-initiated: audio is available with
# native controls, a spectrogram is represented, and no media may autoplay on
# a wall display.
grep -Fq 'detection-detail' "$VIEW_FILE" || fail 'detections must expose a detail view'
grep -Eq '<button|role="button"' "$VIEW_FILE" || fail 'detection detail must be keyboard/tap reachable'
grep -Fq 'aria-label' "$VIEW_FILE" || fail 'detection detail controls must be labelled accessibly'
grep -Eq "<audio[^>]+controls|controls[^>]+<audio|[.]controls[[:space:]]*=[[:space:]]*true|setAttribute\\([\"']controls" "$VIEW_FILE" || \
  fail 'detection detail must expose audio with native controls'
grep -Fq 'spectrogram' "$VIEW_FILE" || fail 'detection detail must expose a spectrogram'
! grep -Eiq '<audio[^>]*autoplay|[.]autoplay[[:space:]]*=' "$VIEW_FILE" || \
  fail 'detection audio must never autoplay'

# The ambient page should provide context for both ends of the day and show
# the compact activity history without turning into a second admin console.
for insight_copy in 'morning' 'evening' 'expected today'; do
  grep -Eiq "$insight_copy" "$VIEW_FILE" || fail "missing ${insight_copy} bird insight"
done
grep -Eiq 'activity[-_ ]?strip|24[-_ ]hour' "$VIEW_FILE" || \
  fail 'frame view must show a compact 24-hour activity strip'

# Audio health is a rolling signal, not a one-time connection result. The UI
# must expose both the meter and freshness timestamp/age to the viewer.
grep -Eiq 'audio[-_ ]?meter' "$VIEW_FILE" || fail 'frame view must expose a rolling live audio meter'
grep -Eiq 'audio[^[:cntrl:]]*(fresh|last|age)|(fresh|last|age)[^[:cntrl:]]*audio' "$VIEW_FILE" || \
  fail 'frame view must expose live audio freshness'

# Long-term value belongs in read-only KPIs: a life list and an explicit
# listening streak, in addition to the existing today counters.
grep -Eiq '(species|life list)[^[:cntrl:]]*(lifetime|ever|all[-_ ]?time)|(lifetime|ever|all[-_ ]?time)[^[:cntrl:]]*(species|life list)' "$VIEW_FILE" || \
  fail 'frame view must expose lifetime species KPI'
grep -Eiq 'streak' "$VIEW_FILE" || fail 'frame view must expose a listening streak KPI'

# Rare/high-confidence notices are presentation-only. They may be announced
# in the page, but must not create a notification permission flow or a write
# endpoint.
grep -Fq 'detection-alert' "$VIEW_FILE" || fail 'frame view must present detection alerts in the page'
grep -Eiq 'rare' "$VIEW_FILE" || fail 'frame view must present rare visitor alerts'
grep -Eiq 'high[-_ ]confidence' "$VIEW_FILE" || fail 'frame view must present high-confidence alerts'
! grep -Fq 'Notification.requestPermission' "$VIEW_FILE" || \
  fail 'rare/high-confidence alerts must not request notification permission'

# Kiosk lighting follows the time of day and reports recovery state explicitly.
grep -Eiq 'night[-_ ]?mode|dim[-_ ]?mode|data-night' "$VIEW_FILE" || \
  fail 'frame view must provide automatic night/dim mode'
grep -Eiq 'stale|offline' "$VIEW_FILE" || fail 'frame view must explain stale/offline data'
grep -Eiq 'clip[^[:cntrl:]]*(retain|retention|kept)|(retain|retention|kept)[^[:cntrl:]]*clip' "$VIEW_FILE" || \
  fail 'frame view must state audio clip retention'

# The sidecar is a narrow, unauthenticated read-only facade. Every proxied
# API location must allow GET only, while no write or authentication surface is
# made public. This protects the enhancements from accidentally widening the
# kiosk's network authority.
grep -Fq 'limit_except GET' "$NGINX_FILE" || fail 'frame API proxy must allow only GET'
grep -Fq 'location /api/ {' "$NGINX_FILE" || fail 'frame API proxy must retain a fail-closed API fallback'
grep -Fq 'return 404;' "$NGINX_FILE" || fail 'unknown frame API paths must fail closed'
! grep -Eiq 'location[^#]*(/auth|/login|/settings|/config|/token|/users)' "$NGINX_FILE" || \
  fail 'frame proxy must not expose auth or configuration endpoints'
! grep -Eiq '(^|[^[:alpha:]])(POST|PUT|PATCH|DELETE)([^[:alpha:]]|$)' "$NGINX_FILE" || \
  fail 'frame proxy must not expose write methods'
! grep -Eiq "credentials[[:space:]]*:[[:space:]]*['\"]include|Notification[.]requestPermission" "$VIEW_FILE" || \
  fail 'frame view must not send browser credentials or request notification permission'

printf 'PASS: BirdNET frame-view contract\n'
