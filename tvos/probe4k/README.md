# Frame 4K Probe

This is a standalone, diagnostic-only Android app for checking whether the Sony TV accepts
a 3840x2160 `SurfaceView` buffer. It has no HOME intent, no network/storage/runtime
permissions, and does not modify FrameTV.

Use the existing pinned TV wrapper from `tvos/`:

```sh
cd tvos
./gradlew -p probe4k :app:assembleDebug :app:testDebugUnitTest
```

Controls: DPAD Left selects the 1080p buffer, Right selects the 4K buffer, Center toggles
the grayscale pattern alignment, Menu toggles the text overlay, and Back exits. The logs use
the `Frame4KProbe` tag. A 4K EGL buffer only demonstrates the buffer path; inspection of the
stripe test pattern is still required to establish visible panel detail.
