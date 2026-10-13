# Documentation

Choose an app and a verified hardware path before building or installing.

## Start here

- [Project overview and browser quick start](../README.md)
- [Device and app compatibility](compatibility.md): requirements versus observed results
- [Android app setup and builds](android-apps.md): existing FrameOS, FrameOS Touch and Probe
- [Hardware access and recovery](hardware-recovery.md): model-specific entry points
- [Component releases](releasing.md): source, artifacts, signing and acceptance

## App guides

| Guide | Applies to |
| --- | --- |
| [Lenovo device setup](device-setup.md) | Original browser slideshow and stock device provisioning |
| [FrameOS mode router](frame-mode-router.md) | Existing app and Lenovo OEM controls |
| [Existing FrameOS touchscreen panel](frameos-touchscreen-panel.md) | Touch adaptation of `com.wyattfleming.frameos` |
| [FrameOS Touch](frameos-touch-app.md) | New `com.wyattfleming.touchframe` app; separate release gates |
| [FrameOS Touch design handoff](frameos-touch-design-handoff.md) | Native/Gecko boundaries and touch design constraints |
| [Art mode](frameos-art-mode.md) | Existing app's separate artwork feed and presentation |
| [Legacy TLS compatibility](legacy-isrg-root-tls.md) | Reviewed old-Android certificate handling |
| [Touch Probe](../frameos/touchprobe/README.md) | Offline diagnostics, not a launcher or recovery tool |
| [Frame TV](../tvos/README.md) | Separate TV app, controls and device-specific evidence |
| [Initial touchscreen preparation](touchscreen-preparation.md) | Historical first build and probe evaluation |

## Shared services and optional integrations

- [API-key permissions](api-key-permissions.md), [curation](curation.md)
- [Frame remote](../companion/README.md)
- [Home Assistant wall panel](home-assistant-wall-panel.md)
- [BirdNET operations](../birdnet/OPERATIONS.md)
- [Optional tagging pipeline](../tagging/RUNBOOK.md); write-capable and not needed for display

## Operations and contribution

- [Troubleshooting](troubleshooting.md), [cache operations](cache-operations.md)
- [Resilience](resilience-operations.md), [upgrade and rollback](upgrade-rollback.md)
- [Variety observer](frame-variety-observer.md)
- [Contributing and public-file boundaries](../CONTRIBUTING.md)
- [Security](../SECURITY.md), [image provenance](images/README.md)

Public docs use synthetic examples and sanitized results. Keep household configs,
credentials, device identifiers, firmware backups and raw operational evidence
outside the repository. An old guide or release's success is not acceptance of
the latest app on every frame with a similar model name.
