# Native Dolby Vision lab — milestone 1

**Goal:** activate the ASUS PG32UCDM's Dolby Vision mode from the Linux Intel
Arc A380 over DisplayPort with a bounded, reproducible signaling test.
**Status: activation confirmed twice on 2026-10-19.** Each ten-second run retained
the test packet for all ten samples, the operator confirmed Dolby Vision mode,
and restoration of the original packet was verified.

This experimental tooling is separate from the application and release packaging.
The signaling experiment uses privileged Intel register writes. It is not a
production playback backend or proof of Dolby video/content-mapping conformance.
The later sections record the successful Profile 5 playback and compositor
experiments. The released app now bundles the [stock-desktop helper](../dv-helper/README.md);
this folder preserves the earlier lab work. Linux HDMI remains unvalidated.

## Run the controls

Requires Python 3.11+, a C compiler, pkg-config, libdrm development files, and
libmpv with Vulkan/Wayland and Dolby Vision reshaping support. Run as the desktop
user, not root. The C snapshot helper builds automatically into `build/dv-lab/`.

From the repository root, collect a read-only snapshot:

```sh
python scripts/dv-lab/dv_lab.py snapshot \
  --card /dev/dri/card1 --connector DP-3 \
  --output build/dv-lab/dp3-baseline.json
```

`--intel-readback` additionally reads the programmed packet registers. This is
validated only for the A380/i915/DisplayPort combination. It requires installed
IGT `intel_reg` and existing noninteractive sudo access for read commands. The
tool resolves the active connector's pipe before reading its GMP packet storage;
it never writes display configuration registers, acquires DRM master, or changes
monitor configuration.
Register storage is evidence of the programmed packet, not an external link capture.

The rendering control requires the [official Dolby Profile 5 sample](https://github.com/DolbyLaboratories/dolby-vision-contents/tree/main/SolLevante_Netflix),
`BL_RPU_dvhe-05_3840x2160@24fps_0_6313.mp4`. Download from:

```text
https://media.githubusercontent.com/media/DolbyLaboratories/dolby-vision-contents/main/SolLevante_Netflix/BL_RPU_dvhe-05_3840x2160%4024fps_0_6313.mp4
SHA256: dacfd04518accd6367530b650dfeea429227df2be171bd99b4bdad36d31bbf9f
```

The test opens a separate fullscreen player on the requested monitor, plays about
two seconds with Dolby metadata, then about two seconds without it. The latter is
an intentional invalid-color control. The player closes afterwards. It does not
quit a running Silage instance. Set `WAYLAND_DISPLAY` to the active session socket.

```sh
timeout --signal=TERM --kill-after=3s 40s env WAYLAND_DISPLAY=wayland-1 \
  python scripts/dv-lab/dv_lab.py profile5 \
  --card /dev/dri/card1 --connector DP-3 --intel-readback \
  --video /home/admin/Videos/Silage-HDR-Tests/Dolby-Profile5-4K.mp4 \
  --output build/dv-lab/profile5-dp3.json

python -m unittest discover -s scripts/dv-lab -v
```

Exit status zero means the **rendering control** passed: Profile 5, the exact
requested monitor, PQ output, and reshaping present only with metadata enabled.
It does not mean native DV passed. Reports always leave
`native_dolby_vision_monitor_signaling_verified` false. EDID reports include only
the Dolby capability blocks, not monitor serial numbers or the complete EDID.

## What was established on this machine

2026-10-18: Intel Arc A380 (`8086:56a5`), i915, CachyOS kernel 7.2.2-1-cachyos,
ASUS PG32UCDM on DP-3, 3840×2160 at 240 Hz, 10 bpc with DSC. The RTX 5090 belongs
to the Windows VM and is not part of the Linux path.

The monitor's live Dolby VSVDB is `0146d0004c025d89606f87` (version 2).
`edid-decode` reports low-latency interfaces and 12-bit 4:4:4 support. EDID
capability alone does not establish an active Dolby Vision link.

The Profile 5 metadata A/B control passed on DP-3. Renderer passes contain
`reshaping` with metadata enabled and omit it after `format=dolbyvision=no`.
Both produce PQ output with zero dropped frames in the short control run.
Programmed packet readback matched the KMS prediction before, during both test
states, and after playback. Desktop HDR metadata was restored after closing.
This establishes source metadata processing, not full
Dolby content-mapping conformance or native monitor signaling.

The programmed Intel GMP packet storage matched the predicted standard HDR SDP:

```text
00871d4c011a02008485823baf2f798a0a1cdf0a3a3d4740090101000901090100000000
```

The final four bytes are storage padding. The KMS metadata has EOTF 2 (PQ),
mastering maximum 265 nits, minimum 0.0001 nit, MaxCLL 265, and MaxFALL 265.
These are a baseline reading of the desktop output, not Dolby-specific magic
values and not a proposed DV test vector.

## The implementation dependency

[Dolby's monitor documentation](https://dolby.my.site.com/accesssupport/s/article/Dolby-Vision-Monitors---Everything-you-need-to-know)
describes a Dolby-specific DRaM InfoFrame for PC monitor output, including the
requirement to retain PQ EOTF in the DisplayPort CEA HDR SDP. It does not publish
the complete byte layout or a matching pixel test vector. The experiment below
now establishes a specific packet that activates this monitor over DisplayPort.

The standard HDR blob can represent the observed activation packet's fields:
the inspected Intel path copies those fields into the DP packet. The successful
experiment changed the programmed packet directly; it did not establish a
compositor-supported API for the application. Absence of a `DOVI_*` KMS property
does not by itself require a kernel patch for this PC signaling variant.

Driver source inspected at upstream Linux revision
`c03c1a14507dfaf8b856c2608f9ac216021fc222` (an encoding reference, **not** claimed
to be the source of the installed CachyOS kernel):

- [`drm_hdmi_infoframe_set_hdr_metadata`](https://github.com/torvalds/linux/blob/c03c1a14507dfaf8b856c2608f9ac216021fc222/drivers/gpu/drm/display/drm_hdmi_helper.c)
  copies the KMS mastering and content-light fields into the infoframe.
- [`intel_dp_hdr_metadata_infoframe_sdp_pack`](https://github.com/torvalds/linux/blob/c03c1a14507dfaf8b856c2608f9ac216021fc222/drivers/gpu/drm/i915/display/intel_dp.c)
  encodes header `00 87 1d 4c`, version/length `01 1a`, and 26 CTA payload bytes.
- [`intel_display_regs.h`](https://github.com/torvalds/linux/blob/c03c1a14507dfaf8b856c2608f9ac216021fc222/drivers/gpu/drm/i915/display/intel_display_regs.h)
  defines the GMP packet data registers and enable bit used for readback.

The experimental [LibreELEC x86 DV policy](https://github.com/CroqueMr/libreelec-x86-DV/blob/3516c9cbcc8bca75eab43907ad017b0083bd428e/projects/Generic/patches/linux/linux-9910-common-dv-policy.patch)
targets standard TV-led DV over native HDMI. Its actual EDID parser returned
`no-standard-dv-interface` for this DisplayPort EDID. The [PGenerator implementation](https://github.com/BigShoots/PGenerator-Plus)
provides an HDMI LLDV reference for Raspberry Pi, not an Intel DisplayPort backend.
Neither is a compatible patch to install on this host for this milestone.

## Working reference and activation experiment

### Working Windows HDMI reference (2026-10-19)

After a Windows guest restart, the operator reported a working physical HDMI
picture with Dolby Vision controls enabled on the ASUS. A read-only NVAPI query
of the RTX 5090 confirmed a physically connected, active, DV-capable display.
The driver returned `hdrMode=2` (normal HDR), RGB/full-range enum values and
10-bpc enum value, with the following mastering metadata values in API order:

```text
R: 35366,14710  G: 8456,39806  B: 6650,2118  White: 15854,16432
Mastering max/min: 1000,0  MaxCLL/MaxFALL: 1022,938
```

`windows_nvapi_snapshot.ps1` reproduces this GET-only configuration query on
Windows with the NVIDIA driver installed. Run it in 64-bit Windows PowerShell:

```powershell
powershell.exe -NoProfile -File .\windows_nvapi_snapshot.ps1 -OutputPath .\windows-hdr.json
```

The raw local report is saved outside the repository at
`/home/admin/Videos/Silage-HDR-Tests/Windows-DV-HDMI-NVAPI.json`, alongside the
physical monitor's registry EDID. These are driver configuration/capability
readings, not captured HDMI packets. The metadata supplied the candidate used
in the successful Linux activation experiment below. NVAPI's optional format
fields alone are not an external measurement of the HDMI pixel format.

### Linux DisplayPort result

`signal_test.py` encodes the Windows-reported mastering values in the existing
Intel static HDR SDP wrapper, retaining PQ EOTF. On the A380, with the ordinary
BT.2020 RGB HDR desktop at 4K/240 Hz and 10 bpc with DSC, the resulting programmed
packet storage was:

```text
00871d4c011a0200268a763908217e9bfa194608ee3d3040e8030000fe03aa0300000000
```

Both ten-second tests retained this packet at every one-second readback sample.
The operator confirmed the monitor entered Dolby Vision mode in both runs.
Both runs verified restoration of the original packet. These results establish
repeatable monitor mode activation. They do not establish video color accuracy,
Dolby content-mapping conformance, or a portable driver/compositor implementation.

Local evidence:

- `/home/admin/Videos/Silage-HDR-Tests/Linux-DP-Signal-Test-01.json`
- `/home/admin/Videos/Silage-HDR-Tests/Linux-DP-Signal-Test-02.json`

To reproduce on this specific machine, validate the plan before applying it:

```sh
python scripts/dv-lab/signal_test.py --plan \
  --reference /home/admin/Videos/Silage-HDR-Tests/Windows-DV-HDMI-NVAPI.json \
  --output build/dv-lab/signal-plan.json

sudo python scripts/dv-lab/signal_test.py --apply --seconds 10 \
  --reference /home/admin/Videos/Silage-HDR-Tests/Windows-DV-HDMI-NVAPI.json \
  --output /home/admin/Videos/Silage-HDR-Tests/Linux-DP-Signal-Test-03.json
```

The apply path is restricted to the validated A380 PCI address, i915, DP-3,
monitor capability block, and a PQ/BT.2020 baseline whose requested packet matches
register readback. Only the GMP packet and its enable bit are changed. The normal
path restores and checks the original packet; a detached watchdog attempts
restoration if the initiating process disappears. Use a new output filename for
each run so existing rollback evidence is preserved. Watchdog activation on a
killed process has not been tested on the live display.

## Next milestone

### Profile 5 plus activation signal

`profile5_signal_demo.py` runs the pinned official Dolby sample as the desktop
user, verifies Profile 5/RPU reshaping/PQ/BT.2020 on DP-3, then invokes only the
bounded signaling helper through sudo for fifteen seconds. The player closes
after restoration. Run it with a new output filename:

```sh
env WAYLAND_DISPLAY=wayland-1 python scripts/dv-lab/profile5_signal_demo.py \
  --video /home/admin/Videos/Silage-HDR-Tests/Dolby-Profile5-4K.mp4 \
  --reference /home/admin/Videos/Silage-HDR-Tests/Windows-DV-HDMI-NVAPI.json \
  --output /home/admin/Videos/Silage-HDR-Tests/Profile5-DP-DV-Demo-02.json
```

The first combined run on 2026-10-19 retained the activation packet for all
15 register samples. All 27 renderer observations reported Profile 5, DP-3,
PQ/BT.2020 output, and RPU reshaping, with VAAPI decode and zero dropped frames.
Packet restoration and original desktop metadata restoration passed. The
operator confirmed Dolby Vision remained active and the picture looked normal
("Yes it looked great"). This completes the combined functional/visual check;
instrumented image accuracy and Dolby conformance remain unverified.
The report is `/home/admin/Videos/Silage-HDR-Tests/Profile5-DP-DV-Demo-01.json`.

### Compositor integration — implemented and hardware-tested

KWin owns the output metadata. Its
[`DrmPipeline::createHdrMetadata`](https://github.com/KDE/kwin/blob/71edcb608b6c95ce89ddab26026505b8b6fe8147/src/backends/drm/drm_pipeline.cpp)
builds mastering fields from monitor EDID and submits the `HDR_OUTPUT_METADATA`
blob. Changing a player file's source tags alone does not give the application
control of that output packet. The audited upstream revision is an integration
reference, not a verified source/build match for the installed package.

The [KWin 6.8.0 patch](kwin-6.8.0-experimental-dv.patch) implements this path on
upstream base commit `066d1e17c945d9a89fee645e10a2bbd77f2e93a6`. Its experimental
interface is registered only with `KWIN_SILAGE_EXPERIMENTAL_DV=1`:

```text
Service:   org.kde.KWin
Path:      /dev/silage/KWin/HdrMetadata1
Interface: dev.silage.KWin.HdrMetadata1
Property:  version -> uint32 (1)
Methods:   acquire(outputName: string) -> token: string
           renew(token: string) -> bool
           release(token: string) -> bool
```

The profile applies only to a matching validated monitor capability block on a
DisplayPort output already using HDR and wide color gamut. It changes the
metadata through KWin's tested atomic DRM commits. No client-provided raw
register addresses or arbitrary metadata payloads are accepted.

Each output has one owning D-Bus connection. Sessions expire after five seconds
without renewal; owner disconnect, release, disabling HDR, and hotplug end them.
Failed restoration is retried while ownership remains reserved. A newly connected
output does not inherit an old session. The backend metadata participates in
subsequent KWin commits, rather than competing with them through register writes.

Silage's `ExperimentalDvSession.kt` uses the interface only with
`SILAGE_EXPERIMENTAL_DV=compositor`. It checks the actual renderer for Profile 5, RPU
reshaping, PQ/BT.2020 output, fullscreen, and the expected monitor. It renews once
per second and releases when those conditions cease; closing the player closes
its dedicated D-Bus connection. Missing compositor support falls back to normal
HDR playback. The application performs no privileged device or register access.

On 2026-10-19 a separate login/display session ran the compiled patched KWin with
an isolated output configuration and private session bus. The existing desktop
was retained on its original VT and restored automatically. Silage's actual
client and renderer passed `ExperimentalDvNativeTest`: the kernel's KMS metadata
matched the profile during five playback checks, VAAPI decoded the Profile 5
sample with zero dropped frames, and closing the client restored normal metadata.
No register writes were used for this run. Evidence:

```text
/home/admin/Videos/Silage-HDR-Tests/Compositor-Native-App-Test.xml
/home/admin/Videos/Silage-HDR-Tests/Compositor-Native-App-Test.log
```

Validation completed:

- Full patched KWin executable built successfully against the local KDE/Qt stack.
- Nine compositor lifecycle/profile test cases passed (11 QtTest results including
  initialization and cleanup).
- App regression run: 319 passed, one optional native HUD test skipped.
- Four private-bus/eligibility client tests are included in that regression run.
- One additional opt-in compositor hardware test passed without skipping.
- The patch applies cleanly to the upstream 6.8.0 base.

The isolated KWin test build used `KWIN_BUILD_KCMS=OFF` and
`KWIN_BUILD_DECORATIONS=OFF`; it is a test build, not a replacement package for
the regular desktop. A normal-session package should retain the distribution's
normal feature/build configuration. The installed desktop remains stock KWin;
this compositor backend requires a session running the patched compositor.
The later [standalone helper](../dv-helper/README.md) is the development app's
stock-desktop alternative, selected by `SILAGE_EXPERIMENTAL_DV=helper` or `1`.

To repeat the application hardware test in such a session:

```sh
SILAGE_NATIVE_DV_TEST_CLIP=/home/admin/Videos/Silage-HDR-Tests/Dolby-Profile5-4K.mp4 \
SILAGE_NATIVE_DV_KMS_TOOL=/home/admin/silo/silage/build/dv-lab/kms-snapshot \
  ./gradlew :app:test --tests '*ExperimentalDvNativeTest' --rerun
```

The source checkout and build are in `/home/admin/silo/kwin-dv-prototype`.
Build dependencies added locally were `extra-cmake-modules`, `vulkan-headers`,
and `plasma-wayland-protocols`. No compositor package was installed system-wide.

Further work is normal-session packaging, colorimetric/reference validation,
broader display transitions/hardware coverage, and testing the Linux HDMI path.
The captured monitor profile and libplacebo's source metadata processing do not
establish complete Dolby content-mapping conformance. This remains an opt-in
prototype. The bounded register experiment in this lab remains separate from
the heartbeat-supervised application helper described above.
