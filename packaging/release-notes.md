## Silage @VERSION@ — Linux and Windows

This release brings Dolby Vision to any GPU on Linux through KDE, and fixes
restarting after an update. If you have 0.5.0 or 0.5.1, Silage offers it as
**Update ready** in the sidebar above Settings.

### What's new

- **Dolby Vision on any GPU** (Arch-based systems and Fedora with KDE Plasma). Settings → Video → **Dolby Vision through KDE** installs an opt-in version of KDE's KWin with a small Silage patch, from a separate signed repository; it stays current with normal system updates and Settings can restore the standard KWin. Works over DisplayPort and HDMI. Tested on an NVIDIA RTX 5090 and Intel Arc A380 with the ASUS PG32UCDM. See [Dolby Vision](https://github.com/evman/silage/blob/v@VERSION@/docs/DOLBY_VISION.md).
- **Windowed mode on Linux.** Press **F11** (or **F** while playing) to switch between fullscreen and a window, and pick how Silage opens in Settings → Interface → Window. Fullscreen remains the default and the best experience.
- **Hardware decoding for every Dolby Vision profile**, including Profile 7 on NVIDIA.
- **No missed openings.** A Dolby Vision title waits on its first frame while the monitor switches, then plays.
- **Copy Playback Info** with C (or Copy details), and a Dolby Vision status row.
- **Send hardware report** (Settings → Video) helps add Dolby Vision for more monitors and GPUs.
- **Updates restart properly on Linux** (0.5.1 could fail with "Failed to exec spawn helper").
- Silage restarts instead of freezing if the playback engine stops responding; server-stored subtitles load when video comes through the server's proxy; Ctrl+Q works during playback; posters without artwork show their title.

**Upgrading from 0.4.x.** Windows: install `Silage-Setup.exe` once and sign in again; the old portable folder can then be deleted. Linux: run the one-command installer below once; updates then arrive with normal system updates.

### Experimental Dolby Vision limitations

This is not a certified or complete Dolby Vision implementation. The current
renderer processes reshaping, FEL residuals and some brightness metadata, but
does not implement Dolby L2/L8 creative trims. The helper sends a fixed signaling
packet. Full tone-mapping/color accuracy and LLDV versus TV-led transport remain
unverified. A monitor DV mode switch is not proof of those properties.

The bundled Linux renderer targets the tested Arch/CachyOS FFmpeg ABI. If its
native libraries cannot load, the app uses the distribution's libmpv instead;
older runtimes may omit Profile 7 enhancement processing and retain normal HDR.
Experimental DV is disabled by default. No separate helper download or custom
compositor is needed. See [setup and validation status](https://github.com/evman/silage/blob/v@VERSION@/docs/DOLBY_VISION.md).

Linux retains native Wayland/Vulkan HDR playback. Windows currently tone-maps HDR
to SDR. Server features depend on your Silo server's capabilities and profile
permissions. Watch Together, TV sign-in and offline downloads are not included.

### Windows 10/11 x64

[Download Silage-Setup.exe](https://github.com/evman/silage/releases/download/v@VERSION@/Silage-Setup.exe) and run it. It installs per user with no
administrator access and no wizard, then starts Silage. No Java or mpv installation
is needed. The portable EXE and ZIP are no longer published: install once, sign in
again, and delete the old portable folder.

The Windows build is unsigned, so SmartScreen may show a first-run warning.
See [Windows instructions](https://github.com/evman/silage/blob/v@VERSION@/docs/WINDOWS.md).

### Linux

**Automatic installer** (adds the signed repository; run it once if you installed Silage before 0.5.0):
```sh
curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
```

To add a repository by hand (apt, dnf or pacman), see [LINUX.md](https://github.com/evman/silage/blob/v@VERSION@/docs/LINUX.md#install).
For full H.264/HEVC support on Fedora, install FFmpeg from [RPM Fusion](https://rpmfusion.org/Howto/Multimedia).

**Other distributions:** extract `silage-linux-x86_64.tar.gz`, then run the
included `install.sh` or launch `silage/bin/silage` directly.

Linux bundles Java and needs your distribution's libmpv 0.37+. Saved settings
remain in the XDG directories; sessions use the desktop keyring when available,
otherwise an owner-only file. Run `silage --check` to verify playback dependencies.
See [Linux instructions](https://github.com/evman/silage/blob/v@VERSION@/docs/LINUX.md).
The Linux download also includes the optional helper and private playback
libraries. Package installers include the helper's host dependencies. Users of the portable build need Python 3, pkexec/polkit, Intel GPU Tools and libdrm before enabling DV.
`silage-playback-runtime-sources.tar.gz` supplies the exact corresponding mpv and
libplacebo sources, licenses, applied patch, build instructions and runtime hashes.

Versioned and stable-named downloads contain the same builds. The signed `SHA256SUMS`
covers the Linux and Windows downloads. The attached `PKGBUILD` contains the
exact Linux tarball checksum for building the Arch package yourself.
