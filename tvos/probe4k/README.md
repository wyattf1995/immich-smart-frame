# Frame 4K Probe

Standalone diagnostic app for a Sony XBR-65A8F running Android 9. It has no HOME
intent and no network, storage, or runtime permissions. It does not modify FrameTV.

The three explicit activities test different routes:

- `.ProbeActivity`: GLES 2 RGB `SurfaceView`, switchable 1920×1080 / 3840×2160.
- `.YuvProbeActivity`: CPU-filled YV12 `ImageWriter`, switchable HD / UHD.
- `.VideoProbeActivity`: one 3840×2160 HEVC Main 5.1 High-tier frame decoded to an opaque `SurfaceView`.

The package is `com.wyattfleming.frametv.probe4k`. Logs use `Frame4KProbe`.
Only the GLES activity has a launcher intent; other activities are shell-launchable.
Left / Right select HD / UHD in the image activities, Center shifts the pattern,
Menu toggles the overlay, and Back exits. The video activity supports Menu / Back.
Hide the overlay before collecting SurfaceFlinger/HWC evidence.

## What counts as evidence

A requested 4K size is intent. Actual EGL/image/decoded dimensions establish the
producer resolution. SurfaceFlinger's active buffer establishes the latched size.
Neither alone proves native panel detail. A 3840×2160 source composed into a
1920×1080 Client target is a failed native-4K route. A direct hardware video layer
requires further composition evidence and, when its display coordinates remain
logical, physical inspection of the one-pixel pattern. A screenshot can capture
only the low-resolution graphics composition and is not independent panel proof.

The video fixture uses neutral chroma, so even a successful video-plane experiment
would establish full-resolution luma detail, not full-resolution RGB photo color.

## Build and validation

Use the pinned Linux Android NAS runtime and the existing `tvos/gradlew` wrapper.
Run `bash tvos/probe4k/tools/nas-build.sh` in an admitted clean source transport.
The script generates two original synthetic HEVC assets with a checksum-pinned
Linux FFmpeg binary. The original lossless fixture must decode exactly to its 4K
reference, but x265 advertises Level 8.5 for that fixture and the Sony did not
admit a decoder for it. The selected Main 5.1 High-tier fixture uses explicit
bitrate limits and must preserve luma within eight code values, keep neutral
chroma exact, and pass independent bitstream profile/level verification. A
source-only over-compression mutation must fail this unchanged detail guard.

The script then runs compilation, lint, JVM tests, and source-only negative tests. Each
negative changes only a production guard, requires an assertion failure, restores
identical source bytes, and reruns unchanged tests. The final full-suite run is
included in the exported report. No user photos or credentials are build inputs.

Declare these NAS artifacts and a fresh local output destination:

- `tvos/probe4k/app/build/outputs/apk/debug/app-debug-unsigned.apk`
- `tvos/probe4k/reports/validation.zip`
- `tvos/probe4k/app/src/main/res/raw/probe4k_hevc.mp4`
- `tvos/probe4k/app/src/main/res/raw/probe4k_hevc_main51.mp4`

The fixture MP4s are generated during the NAS build, not checked-in source. Their
recipes, encoded checksums, decoded comparisons, and exported artifacts bind them
to the build. Signing stays local. Check package identity before updating or
removing the diagnostic app, and verify its buffers are released after exit.

The experiments use bounded work: image planes are capped at 3840×2160, YUV work
coalesces and cancels by row, and video input/dequeue attempts have finite caps.
Native codec calls can still block inside vendor code, so lifecycle timeouts and
real-device cleanup evidence are necessary. These activities are prototypes, not
a production photo player.
