## Silage @VERSION@ — Linux and Windows

This release adds bundled experimental Dolby Vision tooling on Linux and fixes
startup input, version-picker focus and Escape navigation on Linux and Windows.

### What's new

- Linux downloads include the DV helper, portable first-launch setup and a pinned private mpv/libplacebo renderer. Enable **Settings → Video → Experimental Dolby Vision**, restart, and authorize only the helper. The app, media decoders and server connections remain unprivileged.
- Verified source processing and display signaling for Profile 5, Profile 8.1 and Profile 7 MEL/FEL on Intel Arc A380 / i915 with ASUS PG32UCDM over DisplayPort. Other GPUs, monitors and Linux HDMI signaling are not enabled.
- Playback Info shows the Dolby profile, actual FEL enhancement processing, MEL residual status and live helper status. Version choices preserve the exact server file, including copies with the same quality label.
- Helper authorization happens at startup and is reused between movies. Playback close, app EOF, relay failure and missing heartbeats restore ordinary HDR.
- Startup blocks clicks and keyboard input throughout the four-second intro and fade. Selecting a version or dismissing the picker restores keyboard focus to Play.
- Escape dismisses an open playback menu; otherwise it leaves playback directly, removing the intermediate fullscreen-to-windowed transition. The app prepares the browsing page before closing the native player, without waiting for server cleanup.
- Corrected native HDR mouse clicks, seek dragging and motion handling.

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
compositor is needed. See [setup and validation status](https://github.com/evman/silage/blob/v@VERSION@/DOLBY_VISION.md).

Linux retains native Wayland/Vulkan HDR playback. Windows currently tone-maps HDR
to SDR. Server features depend on your Silo server's capabilities and profile
permissions. Watch Together, TV sign-in and offline downloads are not included.

### Windows 10/11 x64

[Download the portable EXE](https://github.com/evman/silage/releases/download/v@VERSION@/Silage-Portable.exe), put it in a writable folder and double-click it.
No Java, mpv installation or administrator rights are needed. First launch
extracts the runtime beside the EXE; later launches reuse it.

[ZIP alternative](https://github.com/evman/silage/releases/download/v@VERSION@/Silage-Windows-x64.zip): extract the whole folder and run `Silage/silage.exe`.
Keep `userdata` when updating. Saved Windows sign-in is tied to your Windows
account; moving to another account or PC may require signing in again.

The Windows build is unsigned, so SmartScreen may show a first-run warning.
See [Windows instructions](https://github.com/evman/silage/blob/v@VERSION@/WINDOWS.md).

### Linux

**Automatic installer:**
```sh
curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
```

**Arch:**
```sh
curl -fLO https://github.com/evman/silage/releases/latest/download/silage-bin-x86_64.pkg.tar.zst && sudo pacman -U silage-bin-x86_64.pkg.tar.zst
```

**Ubuntu 24.04+ / Debian 13:**
```sh
curl -fLO https://github.com/evman/silage/releases/latest/download/silage_amd64.deb && sudo apt install ./silage_amd64.deb
```

**Fedora 40+:**
```sh
sudo dnf install https://github.com/evman/silage/releases/latest/download/silage.x86_64.rpm
```
For full H.264/HEVC support on Fedora, install FFmpeg from [RPM Fusion](https://rpmfusion.org/Howto/Multimedia).

**Other distributions:** extract `silage-linux-x86_64.tar.gz`, then run the
included `install.sh` or launch `silage/bin/silage` directly.

Linux bundles Java and needs your distribution's libmpv 0.37+. Saved settings
remain in the XDG directories; sessions use the desktop keyring when available,
otherwise an owner-only file. Run `silage --check` to verify playback dependencies.
See [Linux instructions](https://github.com/evman/silage/blob/v@VERSION@/LINUX.md).
The Linux download also includes the optional helper and private playback
libraries. Package installers include the helper's host dependencies. Portable
users need Python 3, pkexec/polkit, Intel GPU Tools and libdrm before enabling DV.
`silage-playback-runtime-sources.tar.gz` supplies the exact corresponding mpv and
libplacebo sources, licenses, applied patch, build instructions and runtime hashes.

Versioned and stable-named downloads contain the same builds. `SHA256SUMS`
covers the Linux and Windows downloads. The attached `PKGBUILD` contains the
exact Linux tarball checksum for building the Arch package yourself.
