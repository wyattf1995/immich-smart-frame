# Lenovo CD-3L501F device setup

This procedure was verified on the Lenovo Smart Frame CD-3L501F (`Walnut`)
running the stock Android 10 production build. Other firmware may differ.

## What you need

- a USB-C OTG adapter;
- a USB mouse;
- [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
  (`adb`) on another computer;
- a kiosk-browser APK obtained from its publisher, such as
  [Fully Kiosk Browser](https://www.fully-kiosk.com/);
- the frame and Docker host on the same trusted network.

The frame has no touchscreen. Keep the OTG mouse nearby as recovery input even
after wireless control works.

## Enable USB debugging

1. Open Android Settings with the mouse.
2. Open **About** and activate Developer Options by clicking the build number
   repeatedly.
3. In **Developer options**, enable **USB debugging**.
4. Connect the frame to the computer and approve the debugging prompt.
5. Confirm that ADB sees it:

   ```sh
   adb devices
   ```

If the dialog cannot be approved with the mouse, the hardware star button can
be mapped to a screen tap with an accessibility key-mapping app. On the tested
unit the star button reported scan code 255; this is a recovery technique, not
a runtime dependency.

## Use explicit ADB targets

Developer workstations often have emulators connected. Resolve the frame's
serial first and use `-s` on every command:

```sh
adb devices -l
adb -s FRAME_SERIAL shell getprop ro.product.model
```

Do not run untargeted install, reboot, or settings commands when more than one
ADB transport is listed.

## Optional wireless ADB

With USB debugging already authorized:

```sh
adb -s FRAME_SERIAL tcpip 5555
adb connect FRAME_IP:5555
adb -s FRAME_IP:5555 get-state
```

The stock build is locked (`ro.debuggable=0`), so TCP ADB does not survive a
normal reboot. It is also unauthenticated network-level shell access after the
host key is approved. Use it only on a trusted LAN and do not port-forward 5555.

## Install and configure the browser

Install the APK with the explicit serial:

```sh
adb -s FRAME_IP:5555 install path/to/browser.apk
```

The tested renderer is Fully Kiosk Browser 1.61.2. Configure:

- Start URL: `http://DOCKER_HOST:3000/`
- fullscreen: on;
- action bar: off;
- address bar: off;
- screen saver: off;
- JavaScript: on;
- Fully's privileged JavaScript device interface: off.

The DPR patch in this project does not require Fully's privileged JavaScript
interface or its broad device permissions.

## Lock down the frame network

After provisioning, treat the frame like a single-purpose appliance rather than
a general Android tablet:

1. Place it on a dedicated VLAN or isolated SSID if your network supports it.
2. Allow outbound access only to the Docker host's slideshow port and whatever
   DNS/NTP services your network requires.
3. Deny inbound connections from the rest of the LAN except temporary ADB from
   a trusted workstation during maintenance.
4. Do not leave TCP/5555 reachable after setup.

When you are done with wireless ADB, disable it immediately:

```sh
adb -s FRAME_IP:5555 usb
adb disconnect FRAME_IP:5555
```

If you do not expect to debug again soon, also turn off **USB debugging** in
Developer Options.

## Model-specific stability settings

### Keep Bluetooth off after a failed pairing

On the tested frame, a failed Bluetooth-mouse pairing left SystemUI retrying
the bond continuously and starved the browser. Turning Bluetooth off stopped
the loop immediately. Prefer the OTG mouse for recovery.

### Configure MediaTek DuraSpeed if services are suppressed

If Android repeatedly logs `bringUpServiceLocked, suppress to start service!`
while the browser or FrameOS waits for background work, either disable DuraSpeed
on this dedicated, always-powered kiosk or leave it enabled and explicitly allow
each required application in the firmware's hidden per-app list. FrameOS must be
listed as `com.wyattfleming.frameos`; an exemption for Fully or Key Mapper does
not carry over. The repository's readiness sampler accepts either safe state and
does not change it.

On the tested firmware, the supported command below adds FrameOS to
`PlatformWhitelist`; a package selected through the hidden Settings screen can
appear in `AppWhitelist`. Verify the exact package in either list:

```sh
adb -s FRAME_IP:5555 shell dumpsys duraspeed addwhitelist \
  com.wyattfleming.frameos
adb -s FRAME_IP:5555 shell dumpsys duraspeed status
adb -s FRAME_IP:5555 shell dumpsys duraspeed config
```

The legacy Fully-only recovery sequence disables DuraSpeed globally:

```sh
adb -s FRAME_IP:5555 shell settings put global setting.duraspeed.enabled 0
adb -s FRAME_IP:5555 shell am force-stop de.ozerov.fully
adb -s FRAME_IP:5555 shell am start -n de.ozerov.fully/.FullyActivity
```

Do not clear the browser's application or WebView data unless you intend to
repeat onboarding and restore all settings.

## Android upgrades

The device has an unlockable bootloader and an A/B layout, but replacing the
stock OS is not required for this project. The 2 GB system partition and lack
of vendor support make a GSI upgrade a separate recovery-risk decision. The
server-rendered architecture keeps Android 10 out of the photo-selection and
image-processing path.
