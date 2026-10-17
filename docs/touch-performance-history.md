# FrameOS Touch performance history

One row per build measured on the large frame (159K: 976 MB RAM, 1920x1080, Android 8.1). Budgets live in
`frameos/touchframe/performance-budget.json`. APK size is checked by the NAS gate
(`scripts/verify-touchframe-size-budget.py`, every build). Device numbers come from `device/memory_ab.py` in the
Touch design workspace: 60 s windows, navigation taps only, run after installing on the frame.

| Date | Build | Source | APK MB | Photos idle: Touch PSS MB | Photos idle: CPU % (main + gpu) | Photos idle: app frames/min | Home after 3 min: Touch PSS MB | Touch kills | Budget |
|---|---|---|---|---|---|---|---|---|---|
| 2026-10-17 | 37 | fe16ab39 | 145.5 | 413 | 94.6 (52.8 + 41.8) | 1,416 | 325 | 0 | before budgets |
| 2026-10-17 | 41 run 1 | a77c2059 | 145.5 | 303 | 92.2 (52.6 + 39.6) | 1,393 | 256 | 0 | memory pass; Photos CPU/frames fail (known) |
| 2026-10-17 | 41 run 2 | a77c2059 | 145.5 | 353 | 90.8 (52.0 + 38.8) | 1,643 | 239 | 0 | same |
| 2026-10-17 | 41 run 3 | a77c2059 | 145.5 | 342 | 90.6 (51.7 + 38.9) | 1,344 | 240 | 0 | same |
| 2026-10-17 | 41 run 4 | a77c2059 | 145.5 | 362 | 91.2 (51.7 + 39.5) | 1,345 | 239 | 0 | same |
| 2026-10-17 | 41 run 5 | a77c2059 | 145.5 | 304 | 89.9 (51.0 + 38.9) | 1,325 | 247 | 0 | same |

**Noise floor (build 41, five back-to-back runs, 10/17 14:13–14:47 PDT):** Photos-idle PSS 303–362 MB (spread 59),
Photos CPU 89.9–92.2% (2.3), app frames 1,325–1,643 a minute (318), Home-after-3-minutes PSS 239–256 MB (17). APK size
is identical build to build. Budgets sit above these spreads; the Photos CPU and frame budgets are targets that fail
today (Kiosk's per-frame slide timer, 10/13 audit) and pass once that is fixed.

**Build 37 → 41:** after 3 minutes on Home, Touch holds 240 MB instead of 325 and Android has 362 MB available instead of
264 (the Photos page is released after 2 minutes away). Photos itself is unchanged: build 41 keeps the TextureView
(build 40's SurfaceView never reported first paint on this frame). Caveat: build 41 was measured 5–35 minutes after its
install and build 37 about 80 minutes after its, and Gecko grows with uptime, so the Photos-idle PSS difference
(413 vs a 342 median) is not attributed to these changes.

