# Display compatibility

Reviewed October 12, 2026. This table separates a model-specific access observation
from application compatibility. **Tested** means a described path on an observed
unit; **experimental** means limited evidence with open acceptance checks;
**unsupported** means there is no accepted path or the API/ABI does not fit.
Verify the exact model, Android API level, ABI, display, launcher, and ordinary
app-install behavior on each unit before changing its launcher or firmware.

| Display family | What is known | Browser slideshow | Existing FrameOS app | FrameOS Touch app | Current posture |
| --- | --- | --- | --- | --- | --- |
| Lenovo Smart Frame CD-3L501F | The original project target: stock Android 10 and a no-touch interaction model. | The documented, tested project path. | The existing application and its gesture-oriented guides apply here. | A separate source candidate; it is not a replacement or a validated install on this model. | Keep the established Lenovo path unless a separate Touch acceptance run succeeds. |
| AiMOR PF1007 / PF1007L family | Observed QA unit: printed PF1007 / software PF1007L, Android 6.0.1/API23, ARMv7, 1280×800. Variants still require identification. | Standalone browser path unverified. | **Experimental legacy target:** Photos/Art and touch navigation observed on one unit; auth and later-build acceptance remain separate. **Modern ARM64 target unsupported** on this API23/ARMv7 unit. | Separate development candidate; no accepted PF1007 Touch install. | Model-specific recovery and legacy compatibility work; not a generic flashable target. |
| Frameo BIGASUO 159K family | Observed QA unit: Android 8.1/API27, ARMv7 app path, 1920×1080 touchscreen. Ports and vendor behavior remain unit-specific. | Standalone browser path unverified; Frameo pairing is separate from Android access. | **Experimental legacy target:** observed Photos/Art and Home transitions on a reviewed build, with performance and physical-input gates open. **Modern API29 target unsupported** on this API27 unit. | Separate development candidate; no accepted 159K Touch install. | Use official Frameo access first and preserve the vendor launcher through acceptance. |
| Any other Android photo frame | Marketing compatibility with phone apps does not establish the frame OS, ABI, USB data role, bootloader, or app-install policy. | Test on the local network with a harmless browser page. | Unsupported until API/ABI, rendering, input, and lifecycle checks pass. | Unsupported until it is built and receives a device-specific acceptance run. | Do not reuse another model's recovery, rooting, or firmware procedure. |

The larger frame's observed legacy B7 source was
`ba0a374965578b1f44e9d6fe3efd525595688e1b`. Three Home/Photos round trips reached
usable content; observed Home bounds of 17.474–18.482 seconds missed the 15-second
target. Those UI-poll measurements are not frame-rate or physical-touch results.
Earlier small-frame Photos/Art observations likewise do not certify this later
build. Private device receipts are retained outside Git; no household identifiers
are needed in this public summary. No new Touch APK acceptance is established
here, and minimum SDK alone is not a support promise.

## Product paths

- **Immich Kiosk/browser slideshow** is the original Lenovo-oriented public project path and remains independent of a native app.
- **Existing FrameOS app** is the established application. Its modern target is API 29/ARM64; its legacy Armv7 target exists for explicitly reviewed older hardware. The existing touchscreen Home guide is [FrameOS app touchscreen Home](frameos-touchscreen-panel.md).
- **FrameOS Touch app** is a separate application ID and interaction design. It reuses selected shared components but has independent authentication, configuration, build, and device-acceptance gates. See [FrameOS Touch](frameos-touch-app.md).

For model-specific recovery/access research, start with [hardware recovery](hardware-recovery.md). Historical preparation evidence is kept in [touchscreen preparation](touchscreen-preparation.md); it is not a current deployment recipe.
