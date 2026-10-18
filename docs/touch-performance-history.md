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
| 2026-10-17 | 43 | cd6451df | 145.5 | 355 | 91.0 (52.1 + 38.9) | 1,388 | 221 | 0 | after the Kiosk slide-timer cutover: unchanged, so Kiosk was not the cause |
| 2026-10-17 | 44 run 1 | 45a1bc5f | 145.5 | 313 | 17.4 (12.6 + 4.8) | 86 | 276 | 0 | GeckoView frame gate; all device budgets pass (3 min after install) |
| 2026-10-17 | 44 run 2 | 45a1bc5f | 145.5 | 281 | 11.5 (9.0 + 2.5) | 46 | 257 | 0 | same; all pass |

**Noise floor (build 41, five back-to-back runs, 10/17 14:13–14:47 PDT):** Photos-idle PSS 303–362 MB (spread 59),
Photos CPU 89.9–92.2% (2.3), app frames 1,325–1,643 a minute (318), Home-after-3-minutes PSS 239–256 MB (17). APK size
is identical build to build. Budgets sit above these spreads; the Photos CPU and frame budgets were targets that failed
until build 44 (GeckoView's TextureView redraw loop, see below).

**Build 37 → 41:** after 3 minutes on Home, Touch holds 240 MB instead of 325 and Android has 362 MB available instead of
264 (the Photos page is released after 2 minutes away). Photos itself is unchanged: build 41 keeps the TextureView
(build 40's SurfaceView never reported first paint on this frame). Caveat: build 41 was measured 5–35 minutes after its
install and build 37 about 80 minutes after its, and Gecko grows with uptime, so the Photos-idle PSS difference
(413 vs a 342 median) is not attributed to these changes.

**Latency (device/latency_sampler.py, 10/17).** Build 41: return to Photos after the page was released 4.45 s black,
cold start 7.2–8.0 s to a picture, one warm return 4.0 s after a kernel kill of the hidden page. Build 42 (reserve photo
on load, high priority hint while hidden): 0.58 s, 1.68 s, warm returns 0.55 s (one 1.36 s), no page kills. Budgets in
`performance-budget.json` → `device.latency`.


**Build 43 → 44 (Photos redraw loop).** The 10/13 audit blamed Kiosk's per-frame slide timer. The timer fix went live
at 17:35 on 10/17 and build 43 still measured 91% and 1,388 frames a minute. The cause was GeckoView itself: its
TextureView backend hands Gecko a new surface after every frame (`SurfaceViewWrapper.ListenerWrapper.
onSurfaceTextureUpdated`, GeckoView 144 and 154), so Gecko rebuilt its EGL surface and drew again in a loop.
`TouchGeckoView` passes a frame update to Gecko only when Gecko asks for a new surface.

Results on idle Photos:
- CPU: 91% before, 11.5-17.4% now.
- Frames: 1,388 a minute before, 46-86 now.
- Surface-rebuild log lines: at least 244 in 43 s before, 3 in 5 minutes now.

Home after 3 minutes, 276 then 257 MB: both runs started within minutes of a fresh Touch start. At that point Gecko
holds a second content process (about 30 MB) that the earlier, hour-old runs did not have.
