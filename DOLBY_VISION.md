# Experimental Dolby Vision on Linux

Silage includes the display helper and a private mpv/libplacebo renderer in
the Linux download. No separate helper download, custom KWin build, or system
library replacement is needed. The feature is disabled by default.

This is an experimental, hardware-specific implementation. Monitor mode
activation, source reshaping and Profile 7 enhancement processing have been
verified. Complete Dolby content mapping, creative trims, measured color
accuracy, and standards-compliant LLDV or TV-led transport have not been verified.

## Enable it

1. Install or extract the normal Linux release.
2. Turn on HDR for the target display in KDE's display settings.
3. Open **Settings → Video → Experimental Dolby Vision → Enabled**.
4. Restart Silage and authorize the display helper in the desktop password prompt.
5. Play a supported original Dolby Vision file fullscreen on the matching monitor.

Only the display helper runs as root. Silage, mpv, FFmpeg, media parsing and
server connections run as the desktop user. Authorization happens at startup
and is reused between movies. Canceling it keeps ordinary HDR available without
prompting again during that app run.

DEB, RPM and Arch packages install the root-owned helper and polkit policy as
part of the app. The portable build includes its own helper setup: the first
authorized launch, or a changed helper payload after an update, installs the
fixed helper files and then starts the helper in the same elevated process.
There is no separate terminal installation step. Python 3, pkexec/polkit, Intel
GPU Tools (`intel_reg`) and libdrm are host dependencies; package installations
declare them. A portable installation needs these dependencies from its distro.

## Tested configuration

| Component | Validated configuration |
| --- | --- |
| Desktop | Stock KDE Plasma Wayland, with HDR and wide color gamut enabled |
| GPU | Intel Arc A380 using i915 |
| Monitor | ASUS PG32UCDM with the exact tested Dolby capability block |
| Connection | DisplayPort, 3840×2160, 10-bit PQ/BT.2020 RGB; tested at 240 Hz with DSC |
| Sources | Profile 5, Profile 8.1, Profile 7 MEL and Profile 7 FEL |
| Runtime | Bundled pinned mpv/libplacebo on the tested Arch/CachyOS FFmpeg ABI |

Other GPUs, displays and Linux HDMI signaling are not enabled. Windows still
tone-maps HDR content to SDR and does not use this helper.

The bundled renderer is preferred when its native dependencies are compatible.
It was built against the current Arch FFmpeg ABI. On incompatible distributions,
Silage falls back to the distribution's libmpv. Such runtimes may not support
Profile 7 enhancement processing; the app reports the missing condition and
retains normal HDR rather than forcing the monitor's DV mode.

## Read Playback Info

Press **I** during playback:

- **Source format** identifies the source's Dolby profile.
- **Enhancement layer: FEL · GPU processing active** means an actual enhancement
  composition pass is present. FEL picture data is being processed.
- **Enhancement layer: MEL · no extra picture layer required** means decoded
  metadata identifies zero residual picture correction; reshaping is still required.
- **DV helper: Signal active (experimental)** means the helper acknowledged the
  validated signal request. Unsupported or missing conditions are shown instead.
- **Display: HDR passthrough** describes PQ/HLG rendering, not a verified Dolby
  transport mode. The label alone does not establish Dolby Vision output.

On the details page, **Versions** selects an exact server file and shows its
reported Dolby profile. A title with one file cannot offer another profile.

Closing playback restores ordinary HDR while retaining app authorization.
Closing Silage ends the helper. An independent guardian restores after app EOF,
relay death, or five seconds without heartbeats. Changed display topology ends
the signal. Direct register access still cannot be fully synchronized with
compositor modesets; this remains experimental.

## What remains to validate

The current renderer handles reshaping, FEL residual composition and some
brightness metadata. Its Dolby metadata mapping does not implement L2/L8
creative trims. The helper uses a fixed captured signaling packet; it does not
forward per-scene creative adjustments to the monitor.

Next validation work is controlled metadata-response tests, comparison against
a verified Dolby reference pipeline at matching display targets, and instrumented
measurements of white point, grayscale, brightness tracking and color patches.
An OSD mode switch, a normal-looking movie or zero dropped frames cannot replace
those checks. Distinguishing LLDV from TV-led output also needs verified transport
evidence; this release does not claim either mode.

See the [helper implementation and hardware verification](scripts/dv-helper/README.md)
and [corresponding runtime source/build instructions](scripts/dv-helper/BUILDING-RUNTIME.md).
The release includes `silage-playback-runtime-sources.tar.gz`, licenses, revision
identifiers, the applied patch and library hashes. Private media, authenticated
server URLs, credentials and local settings are not distributed.

## Disable or remove

Choose **Disabled** in the Video setting and restart Silage. Ordinary playback
does not depend on the helper. Removing a system package removes its installed
helper and policy. For helper files installed by a portable app, close all Silage
instances, then remove only these files if you no longer need them:

```sh
sudo rm /usr/libexec/silage-dv-helper/silage-dv-helper \
  /usr/libexec/silage-dv-helper/kms-snapshot \
  /usr/share/polkit-1/actions/dev.silage.dv-helper.policy
sudo rmdir /usr/libexec/silage-dv-helper
```
