# Frame server-health publisher

`publish-frame-health.py` updates only Home Assistant's
`sensor.frame_server_health`. It runs the reviewed BirdNET watchdog as a child,
accepts only its fixed audio-health report lines, and inspects only
`immich-kiosk`, `birdnet-go`, and `nest-audio-bridge` with a fixed Docker format.
It never reads Docker logs or raw inspect JSON.

The state is `healthy` only when the watchdog reports healthy, fresh audio and
every fixed container is running, non-OOM, and healthy where it has a Docker
healthcheck. A stale report is `degraded`; missing, malformed, timed-out, or
failed evidence is `unknown`. Attributes are scalar JSON only:
`observedAt`, `audioLastAt`, `audioHealthy`, and each service's restart, healthy,
and OOM fields. `observedAt` is epoch milliseconds; HA derives staleness itself.

Install the publisher, watchdog, and loop outside the repository. Copy
`frame-health-loop.env.example` to a root-owned mode-`0600` configuration file,
set the local Home Assistant URL, dedicated root-owned webhook ID file, and the
pinned `FRAME_HEALTH_PYTHON_IMAGE`, then run:

```sh
bash /mnt/user/appdata/frame-review-soak-20260904/monitor/frame-health-loop.sh \
  /mnt/user/appdata/frame-review-soak-20260904/monitor/frame-health.env
```

The NAS host collects bounded watchdog and fixed Docker-inspect evidence, then
pipes it to an ephemeral, pinned companion-Python container. That container has
no Docker socket or host-data access: it receives only `publisher.py`, the
dedicated read-only webhook ID file, and evidence on stdin. It runs read-only with
all capabilities dropped, no-new-privileges, a 64 MiB memory limit, a 32 PID
limit, 0.1 CPU, and an 8 MiB `/tmp` tmpfs. The host loop holds a nonblocking
`flock`, publishes every 60 seconds, and logs only failure/recovery transitions.
The webhook ID is read in memory; it is never an argument, environment value, log
field, or persistent payload. It authorizes only the local Home Assistant health
webhook and is sent without an Authorization header. The portable REST token mode
remains available for explicit operator use, but the loop never mounts or uses it.
The watchdog continues to obtain BirdNET
credentials only from its existing private auth file. A failed HA POST does not
trigger notifications or change any other entity.
