# KigEyes IMX675 camera driver

Out-of-tree Linux V4L2 sensor-subdevice driver for **KigEyes IMX675 modules**
on Raspberry Pi CM5, connected directly or through the KigEyes mainboard muxes.

Requires the module's **CH32 management controller**, with camera protocol
major version 1, for power sequencing, reset, oscillator enable and master/slave
selection. This is not a generic driver for arbitrary IMX675 modules. Camera
firmware must already be installed; this package does not flash it.

## Scope and features

This core package contains the kernel driver, register tables, four device-tree
overlays and build/install support. It uses the existing Raspberry Pi CSI
receiver and Linux pca954x I2C mux drivers. It does not implement an ISP.

| Mode | Active pixels | Output | Maximum sensor rate |
| --- | --- | --- | --- |
| Full pixel | 2608 x 1964 | RAW12, four lanes at 1188 Mbit/s/lane | 60.11 fps |
| H2V2 binning | 1304 x 982 | RAW12, four lanes at 594 Mbit/s/lane | 80.76 fps |

Binning retains the full field of view and defaults to 30 fps. Both modes use
SRGGB12 Bayer order and the module's 24 MHz oscillator. These are sensor timing
limits, not guaranteed application/display throughput. No HDR mode is exposed.

V4L2 controls include exposure, analogue gain and vertical blanking, plus timing
metadata: pixel rate, link frequency and horizontal blanking. Exposure and
blanking use sensor-line units; blanking affects frame duration and exposure range.

Libcamera sensor integration/PiSP tuning are separate from this core export.
Installing only this package does not guarantee `rpicam-*` or `libcamerasrc`
support. Stereo calibration, corrected display, recording and streaming are
also separate userspace components.

## Electrical safety

- Auxiliary camera-connector pins carry **1.8 V XHS/XVS**, not normal Raspberry
  Pi camera power enables. Do not drive them at 3.3 V.
- The overlays disable the relevant camera regulator nodes and claim relevant
  Pi pads as inputs. The installer disables firmware camera auto-detection.
  Do not combine these with conflicting camera power-control overlays.
- Official CM5 IO CAM/DISP1 pin 17 has a physical 2.2 kOhm pull-up to 3.3 V;
  pin 18 is not routed. Software cannot remove that pull-up. Isolate it or the
  affected FFC conductor before connecting sensor synchronization signals.
- Connect master/slave XHS/XVS only after checking connector mapping and voltage
  levels. A slave needs a running master and properly wired timing signals.
- CH32 firmware owns the oscillator enable, including open-drain drive, and
  sequences sensor rails. Do not independently drive these signals from Linux.

Verify wiring before powering modules. These overlays are specific to KigEyes.

## Build and install

Latest build check: Raspberry Pi OS on CM5, kernel `6.18.50+rpt-rpi-2712`.
Other kernels may require API changes. This is not an upstream kernel driver.

Install a C toolchain, `make`, `device-tree-compiler`, and headers matching the
running kernel. Confirm `/lib/modules/$(uname -r)/build` exists. From this folder:

```sh
make
```

This builds `imx675_kigeyes.ko` and all four overlays:

| Overlay | Wiring |
| --- | --- |
| `kigeyes-imx675` | Single module on CAM0; default |
| `kigeyes-imx675-dual` | Direct CAM0 and CAM1, independent timing |
| `kigeyes-imx675-dual-sync` | Direct CAM0 master and CAM1 slave, safe XHS/XVS wiring required |
| `kigeyes-imx675-mainboard-sync` | KigEyes mainboard ports 0 and 3, sync links required |

After reading the electrical warnings, select the appropriate overlay:

```sh
IMX675_OVERLAY=kigeyes-imx675 sh ./install.sh
sudo reboot
```

The installer builds and installs the module/overlays, runs `depmod`, and edits
`/boot/firmware/config.txt`: it disables camera auto-detection and replaces the
selected KigEyes IMX675 overlay. Back up that file and check for conflicting
overlays first. Rebuild/install after kernel updates; there is no DKMS integration.

For the mainboard variant it also installs
`systemd/kigeyes-imx675-mainboard.conf`, a camera-ID drop-in for the optional
KigEyes display service. It does not install or start that service. Kernel
capture does not require it.

## Mux routing and synchronization

The mainboard overlay declares TCA9548A at `0x70`: physical port 0 is channel 7,
CSI0, timing master; physical port 3 is channel 0, CSI1, timing slave. Each
module has sensor address `0x1a` and controller address `0x30` on its own mux
channel. Linux bus numbers are dynamic; identify cameras by their device-tree
paths rather than hard-coded bus numbers.

Mainboard firmware must establish the MIPI switch enable/routing prerequisites.
The overlay has board-specific switch-select GPIO configuration, not a generic
live MIPI-switch API. Connect the mainboard's XHS/XVS links between these ports.

Slave sync outputs are high impedance. The read-only module parameter `free_run`
is a bench diagnostic for independent timing while retaining high-impedance
outputs on a device described as a slave. Leave the default (`false`) for sync.

## Power management

Runtime resume requests CH32 power-up and programs the sensor. Runtime suspend
requests sensor standby, declares host quiescence only on success, and requests
the CH32 rail-off sequence. A failed guarded shutdown falls back to forced-safe
power-off. Linux halt/reboot also has a shutdown callback because mainboard
power can remain present after Linux stops. This cannot protect against a dead
I2C bus, system hang or sudden power loss.

On 2026-10-07 the updated module built and loaded; start/stop tests confirmed
both controllers reached OFF, rail-enable masks cleared and 1.8 V telemetry
fell to zero. The actual halt/reboot callback and total post-halt power still
need verification during an intended system shutdown.

## Verify and troubleshoot

After reboot:

```sh
uname -r
modinfo imx675_kigeyes
sudo dmesg | grep -E 'imx675|rp1-cfe'
ls /sys/bus/i2c/drivers/imx675/
```

A successful probe reports a KigEyes IMX675 and its sync role. With `v4l-utils`,
inspect `media-ctl -p -d /dev/mediaN` for the appropriate CSI receiver. Media and
video device numbers depend on kernel configuration; do not assume `/dev/video0`.

For probe failures check power, CH32 firmware/protocol, cable wiring, overlay
selection and mainboard switch enable. For a stalled slave check its master's
capture and XHS/XVS wiring. If kernel enumeration works but libcamera rejects
the camera, the separate IMX675 libcamera integration may be missing.

## Licensing and attribution

The core release is GPL-2.0-only. See `LICENSE` for its exact file scope and
`COPYING` for the complete terms. This component-specific license supersedes
the parent project's default for those files, not for unrelated applications.
Register initialization values are adapted from FRAMOS's GPL-2.0 IMX675 driver,
copyright (c) 2024 Framos, at revision
`4e772feec7f21e5ca3d485142c7df03a95c75f3c`:

https://github.com/framosimaging/framos-jetson-drivers/tree/4e772feec7f21e5ca3d485142c7df03a95c75f3c

Preserve upstream attribution. The FRAMOS checkout is not a build dependency.
Sony datasheets/manuals are not included in this package.
