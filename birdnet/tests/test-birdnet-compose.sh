#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/docker-compose.yaml"
ENV_FILE="$ROOT_DIR/.env.example"
CONFIG_FILE="$ROOT_DIR/config/config.yaml"
OPS_FILE="$ROOT_DIR/OPERATIONS.md"
WATCHDOG_FILE="$ROOT_DIR/scripts/birdnet-watchdog.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

for required_file in "$COMPOSE_FILE" "$ENV_FILE" "$CONFIG_FILE" "$OPS_FILE"; do
  [[ -f "$required_file" ]] || fail "missing required file: $required_file"
done

grep -Fq 'ghcr.io/tphakala/birdnet-go:20260823@sha256:' "$COMPOSE_FILE" || \
  fail 'BirdNET-Go image must use an immutable release digest'
grep -Eq 'ghcr.io/tphakala/birdnet-go:20260823@sha256:[0-9a-f]{64}' "$COMPOSE_FILE" || \
  fail 'BirdNET-Go image digest must be a complete sha256 digest'
grep -Fq 'BIRDNET_BIND_IP:?Set BIRDNET_BIND_IP' "$COMPOSE_FILE" || \
  fail 'web port must fail closed until a LAN bind address is provided'
grep -Fq 'BIRDNET_CONFIG_DIR:?Set BIRDNET_CONFIG_DIR' "$COMPOSE_FILE" || \
  fail 'config must resolve to an explicit persistent Unraid appdata path'
grep -Fq 'BIRDNET_DATA_DIR:?Set BIRDNET_DATA_DIR' "$COMPOSE_FILE" || \
  fail 'data must resolve to an explicit persistent Unraid appdata path'
grep -Fq 'http://127.0.0.1:8080/health' "$COMPOSE_FILE" || \
  fail 'container healthcheck must use the unauthenticated /health endpoint'
grep -Fq ':/config' "$COMPOSE_FILE" || fail 'config must be persistent'
grep -Fq ':/data' "$COMPOSE_FILE" || fail 'data and clips must be persistent'
grep -Fq '/config/hls:exec,size=50M' "$COMPOSE_FILE" || fail 'HLS segments must use the documented tmpfs'
grep -Fq '/tmp:' "$COMPOSE_FILE" || fail 'read-only root requires writable tmpfs for startup logs'
grep -Fq 'read_only: true' "$COMPOSE_FILE" || fail 'container root filesystem must be read-only'
grep -Fq 'no-new-privileges:true' "$COMPOSE_FILE" || fail 'container must set no-new-privileges'
grep -Fq 'cap_drop:' "$COMPOSE_FILE" || fail 'container must explicitly drop Linux capabilities'
grep -Fq -- '- ALL' "$COMPOSE_FILE" || fail 'container must drop every Linux capability'
grep -Fq 'BIRDNET_BIND_IP=' "$ENV_FILE" || fail 'sample env must document the LAN bind address'
grep -Fq 'WEB_PORT=8090' "$ENV_FILE" || fail 'sample env must use the reserved BirdNET host port'
grep -Fq 'BIRDNET_CONFIG_DIR=/mnt/user/appdata/birdnet-go/config' "$ENV_FILE" || \
  fail 'sample env must keep config off the Unraid boot flash'
grep -Fq 'BIRDNET_DATA_DIR=/mnt/user/appdata/birdnet-go/data' "$ENV_FILE" || \
  fail 'sample env must keep data off the Unraid boot flash'
! grep -Eq '192\.168\.86\.' "$ENV_FILE" || fail 'public sample env must not publish the private LAN address'
grep -Fq 'streams: []' "$CONFIG_FILE" || fail 'sample config must be safe before an RTSP source is installed'
grep -Eq '#[[:space:]]+url: rtsp://[^[:space:]]+' "$CONFIG_FILE" || \
  fail 'sample config must include an explicit RTSP URL placeholder'
grep -Fq 'RTSP_USER' "$CONFIG_FILE" || fail 'RTSP placeholder must show credential indirection'
grep -Fq 'thumbnails:' "$CONFIG_FILE" || fail 'Birds dashboard must configure species thumbnails explicitly'
grep -Fq 'summary: true' "$CONFIG_FILE" || fail 'species summary must include bird images'
grep -Fq 'recent: true' "$CONFIG_FILE" || fail 'recent detections must include bird images'
grep -Fq 'imageprovider: avicommons' "$CONFIG_FILE" || fail 'bird images must prefer the attribution-aware AviCommons provider'
grep -Fq 'fallbackpolicy: all' "$CONFIG_FILE" || fail 'bird images must fall back across supported providers'

# Audio clips contain household audio and need a deterministic, age-based
# retention policy. A usage-only policy can retain old clips indefinitely on a
# mostly-empty disk, so the 30-day age bound is part of the tracked safe
# configuration. Opus keeps the always-on archive materially smaller than WAV.
grep -Eq '^      type:[[:space:]]*opus[[:space:]]*$' "$CONFIG_FILE" || \
  fail 'audio export must use Opus rather than unbounded WAV clips'
grep -Eq '^        policy:[[:space:]]*age[[:space:]]*$' "$CONFIG_FILE" || \
  fail 'audio clip retention must use an explicit age policy'
grep -Eq '^        maxage:[[:space:]]*30d[[:space:]]*$' "$CONFIG_FILE" || \
  fail 'audio clip retention must cap clips at 30 days'
! grep -Eq '^        maxusage:' "$CONFIG_FILE" || \
  fail 'audio clip retention must not rely on a usage-only fallback'

[[ -f "$WATCHDOG_FILE" ]] || fail 'tracked read-only BirdNET watchdog artifact is missing'
[[ -x "$WATCHDOG_FILE" ]] || fail 'BirdNET watchdog artifact must be executable'
grep -Eq '^#!.*(ba)?sh([[:space:]]|$)' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog artifact must be a directly runnable shell script'
grep -Eq 'curl.*health/audio|health/audio.*curl' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog must query the audio health/freshness endpoint'
grep -Eiq 'fresh|stale|audio.*(age|last)|last.*audio' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog must evaluate real audio freshness, not only process liveness'
grep -Eiq 'docker[[:space:]]+inspect.*(RestartCount|restart)|RestartCount' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog must report container restart counts'
grep -Eq 'command -v "\$1"[[:space:]]*>/dev/null' "$WATCHDOG_FILE" || \
  fail 'successful watchdog dependency checks must stay silent'
grep -Fq '"$container" >/dev/null' "$WATCHDOG_FILE" || \
  fail 'successful watchdog container-existence probes must stay silent'
grep -Fq 'sources_are_healthy >/dev/null' "$WATCHDOG_FILE" || \
  fail 'successful watchdog source-state predicates must stay silent'
grep -Eiq '(^|[[:space:]])df([[:space:]]|[-])' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog must check available disk space'
grep -Fq 'birdnet-go' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog must check the BirdNET-Go container'
grep -Fq 'nest-audio-bridge' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog must check the audio bridge container'

# This is an observer/reporting tool. It may inspect state and emit an alert,
# but it must never restart or recreate a service behind the operator's back.
! grep -Eiq 'docker[[:space:]]+(restart|start|stop|kill|rm)|docker[[:space:]]+compose.*(up|down|restart|start|stop|rm)' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog must not perform automatic container restarts'
! grep -Eiq '(^|[[:space:]])(mkdir|touch|rm|mv|cp|tee|install|truncate)([[:space:]]|$)|(^|[^<])>[[:space:]]*[^=]' "$WATCHDOG_FILE" || \
  fail 'BirdNET watchdog must remain read-only and avoid filesystem writes'

grep -Fq 'backup' "$OPS_FILE" || fail 'operations notes must cover backups'
grep -Fq 'rollback' "$OPS_FILE" || fail 'operations notes must cover rollback'
grep -Fq '/boot/config/birdnet-go/' "$OPS_FILE" || fail 'operations must use a persistent Unraid Compose project'
grep -Fq '/mnt/user/appdata/birdnet-go/' "$OPS_FILE" || fail 'operations must use Unraid appdata for state'
grep -Fq 'bash ./scripts/birdnet-watchdog.sh' "$OPS_FILE" || \
  fail 'operations must invoke the boot-flash watchdog through bash on Unraid'
grep -Eiq 'UNVERIFIED.*(full[[:space:]]+Unraid|Unraid.*reboot)|full[[:space:]]+Unraid.*reboot.*UNVERIFIED' "$OPS_FILE" || \
  fail 'operations must mark full Unraid reboot recovery UNVERIFIED'
grep -Eiq 'UNVERIFIED.*(physically|physical).*test|(physically|physical).*test.*UNVERIFIED' "$OPS_FILE" || \
  fail 'operations must keep reboot recovery unverified until physically tested'

if command -v docker >/dev/null 2>&1; then
  compose_json=$(BIRDNET_BIND_IP=192.0.2.10 \
    BIRDNET_CONFIG_DIR=/mnt/user/appdata/birdnet-go/config \
    BIRDNET_DATA_DIR=/mnt/user/appdata/birdnet-go/data \
    docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    --profile nest-audio \
    config --format json)

  python3 - "$compose_json" <<'PY'
import json
import sys

config = json.loads(sys.argv[1])
services = config.get("services", {})

def require_bounded_resources(name, service, max_cpus, max_memory, max_pids):
    for key in ("cpus", "mem_limit", "pids_limit"):
        if key not in service:
            raise AssertionError(f"{name} must set {key}")
    cpus = float(service["cpus"])
    memory = int(service["mem_limit"])
    pids = int(service["pids_limit"])
    assert 0 < cpus <= max_cpus, (name, "cpus", cpus)
    assert 0 < memory <= max_memory, (name, "mem_limit", memory)
    assert 0 < pids <= max_pids, (name, "pids_limit", pids)

def require_json_file_logging(name, service):
    logging = service.get("logging")
    assert logging and logging.get("driver") == "json-file", (name, logging)
    options = logging.get("options", {})
    assert options.get("max-size"), (name, "max-size", options)
    assert options.get("max-file"), (name, "max-file", options)
    size = options["max-size"].lower()
    assert size.endswith("m"), (name, "max-size", size)
    assert 0 < int(size[:-1]) <= 10, (name, "max-size", size)
    assert 0 < int(options["max-file"]) <= 5, (name, "max-file", options["max-file"])

assert "birdnet-go" in services, "birdnet-go service is missing"
assert "nest-audio-bridge" in services, "nest-audio-bridge service is missing"

require_json_file_logging("birdnet-go", services["birdnet-go"])
require_bounded_resources("birdnet-go", services["birdnet-go"], 2.0, 2 * 1024**3, 256)
require_json_file_logging("nest-audio-bridge", services["nest-audio-bridge"])
require_bounded_resources("nest-audio-bridge", services["nest-audio-bridge"], 1.0, 512 * 1024**2, 128)
PY
else
  printf 'SKIP: docker is not installed; static BirdNET invariants passed\n'
fi

printf 'PASS: BirdNET-Go deployment invariants\n'
