# Resilience operations

This runbook is intentionally read-only. It provides evidence for an operator
to decide whether to repair a dependency; it never restarts Kiosk, Immich, Home
Assistant, Unraid services, or the frame automatically.

## Kiosk liveness and dependency readiness

Compose uses Kiosk's `--livecheck` for Docker liveness. It confirms that the
local Kiosk process serves `/livez`. Kiosk's `--readycheck` probes its own
`/readyz` endpoint, which performs a bounded authenticated Immich dependency
read. The two signals are deliberately separate:

- a failed live check means the Kiosk process needs operator attention;
- a failed ready check can mean Immich, the API key, or the network is down;
  restarting Kiosk blindly would add churn without repairing that dependency.

Run a one-time sample from the Docker host or schedule it with the host's
normal cron/monitoring mechanism:

```sh
./scripts/check-frame-readiness.sh
```

It exits nonzero on a failed requested check, so it can feed an existing alert
mechanism. It does not send alerts itself and does not claim an alerting service
is installed.

## Full-stack power or reboot audit

Before any planned power work, record a passing baseline and keep the verified
input snapshot and Android USB recovery cable available:

```sh
./scripts/check-frame-readiness.sh \
  --array-check-command 'YOUR_READ_ONLY_UNRAID_ARRAY_CHECK' \
  --ha-check-command 'YOUR_READ_ONLY_HOME_ASSISTANT_CHECK' \
  --adb DEVICE_SERIAL \
  --router-config /data/local/tmp/frame-mode-router.conf
```

The two optional command hooks are deliberately supplied by the local operator:
this public repository has no household endpoint, Unraid API contract, or Home
Assistant credential. Keep them read-only. For example, an array hook may check
the host's documented array-started state; an HA hook may make an authenticated
or LAN-local health request using a secret kept outside this repository.

After power returns and dependent services have settled, rerun the same command
and verify all requested checks. Then physically verify one gesture in each
direction and the three mapped buttons. The router check only reports shell
state; it cannot prove OEM hardware input.

## Known external limits

This repository cannot prove that a UPS exists, that Unraid BIOS settings restore
power automatically, that the array mounts, or that Android resumes wireless ADB
after a reboot. On the tested locked Android 10 frame, wireless ADB may require
the documented trusted-USB recovery path after an ordinary reboot. The sampler
reports Key Mapper's sysbridge as informational: direct raw-key FrameOS controls
remain the supported path when sysbridge is absent. Treat those as operator
prerequisites, not checks this script can manufacture.
