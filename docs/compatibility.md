# Display compatibility

This table separates a model-specific access observation from application compatibility. It is not a purchase guide, flashing guide, or deployment approval. Verify the exact model, Android API level, ABI, display, launcher, and ordinary app-install behavior on each unit before changing its launcher or firmware.

| Display family | What is known | Browser slideshow | Existing FrameOS app | FrameOS Touch app | Current posture |
| --- | --- | --- | --- | --- | --- |
| Lenovo Smart Frame CD-3L501F | The original project target: stock Android 10 and a no-touch interaction model. | The documented, tested project path. | The existing application and its gesture-oriented guides apply here. | A separate source candidate; it is not a replacement or a validated install on this model. | Keep the established Lenovo path unless a separate Touch acceptance run succeeds. |
| AiMOR PF1007 / PF1007L family | Public PF1007L research describes an Allwinner A33, Android 6/API 23, and ARMv7. Variants and labels must still be identified on-device. | Possible only after ordinary browser and network checks. | Use only the explicit legacy Armv7 target after confirming API/ABI and an app-install trial. The API 29/ARM64 modern target does not fit the reported configuration. | Its minimum API is 23, but it remains an unbuilt, uninstalled candidate and has no PF1007-family acceptance claim. | Treat as model-specific recovery and compatibility work, not a generic FrameOS target. |
| Frameo BIGASUO 159K family | A 15.6-inch touchscreen Frameo model has been evaluated in this project. The exact Android version, ABI, ports, and vendor behavior remain unit-specific. | Check it independently; Frameo pairing and browser behavior are separate from Android access. | A historically observed legacy build and QA evidence apply only to the exact reviewed unit and build. | Separate source candidate; no Touch APK, install, or physical acceptance claim. | Use the official Frameo access path first, then preserve the vendor launcher until ordinary-app acceptance is complete. |
| Any other Android photo frame | Marketing compatibility with phone apps does not establish the frame OS, ABI, USB data role, bootloader, or app-install policy. | Test on the local network with a harmless browser page. | Unsupported until API/ABI, rendering, input, and lifecycle checks pass. | Unsupported until it is built and receives a device-specific acceptance run. | Do not reuse another model's recovery, rooting, or firmware procedure. |

## Product paths

- **Immich Kiosk/browser slideshow** is the original Lenovo-oriented public project path and remains independent of a native app.
- **Existing FrameOS app** is the established application. Its modern target is API 29/ARM64; its legacy Armv7 target exists for explicitly reviewed older hardware. The existing touchscreen Home guide is [FrameOS app touchscreen Home](frameos-touchscreen-panel.md).
- **FrameOS Touch app** is a separate application ID and interaction design. It reuses selected shared components but has independent authentication, configuration, build, and device-acceptance gates. See [FrameOS Touch](frameos-touch-app.md).

For model-specific recovery/access research, start with [hardware recovery](hardware-recovery.md). Historical preparation evidence is kept in [touchscreen preparation](touchscreen-preparation.md); it is not a current deployment recipe.
