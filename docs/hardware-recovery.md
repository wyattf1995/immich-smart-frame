# Hardware recovery and access

Recovery/access work is separate from application setup. Start by recording the physical model label, software-reported model, Android API level, ABI, display characteristics, current launcher, and ordinary app-install behavior. Keep device identifiers, network details, backups, credentials, and any personal media out of public issue reports and documentation.

## Model-specific access

### Frameo devices

Frameo documents an Android Debug Bridge access flow through its own settings: enable **Beta Program**, then **ADB Access**, and use a data-capable USB connection. Follow the current [Frameo support instructions](https://support.frameo.com/hc/en-us/articles/6126183308434--How-to-Enable-ADB-on-Your-Frame) for a Frameo device.

That flow is a debugging-access option. It does not establish root access, persistent ADB after reboot, a compatible Android API/ABI, or permission to replace a launcher. A Frameo-branded model must still be checked as its own device.

### AiMOR PF1007 / PF1007L family

The public [PF1007L hardware report](https://github.com/wesbos/photo-frame-dashboard/blob/main/docs/allwinner-a33-pf1007l.md) and its [boot-access background](https://github.com/wesbos/photo-frame-dashboard/blob/main/docs/allwinner-a33-boot-access.md) are useful model-matching research references. They are not a universal procedure: production variants can differ in board layout, storage, boot chain, USB role, and firmware.

Do not apply a boot image, SD payload, recovery image, or rooting method from another unit solely because the name looks similar. First preserve stock state and validate a read-only identification/ordinary-install path. A card that changes boot behavior is not a firmware backup, and a root shell alone does not make an Android app compatible.

### Wes Bos's public walkthrough

[Wes Bos's photo-frame dashboard repository](https://github.com/wesbos/photo-frame-dashboard) is an inspiration and research source for several inexpensive frames. Its documents cover more than one vendor and chipset. Treat each guide as model-specific background, not as instructions for a different display.

## Safe sequence

1. Preserve the stock launcher and data until a verified backup and rollback plan exists.
2. Identify the device and test normal app installation before changing HOME, boot, or firmware behavior.
3. Check API level and ABI against the intended APK. An APK that installs is not proof that rendering, touch, storage, memory, or reconnect behavior works.
4. Use a small, reversible acceptance pass: launch, touch, orientation, network reconnect, Settings return, and a power-cycle check.
5. Keep source builds, QA signing, installation, and physical acceptance as separate records.

For app compatibility, use the [display compatibility table](compatibility.md). For the historical October 2026 preparation build, see [touchscreen preparation](touchscreen-preparation.md).
