## Silage @VERSION@ — Linux and Windows

This release fixes playback on current Silo server versions and adds a few
small requests. If you have 0.5.0, Silage offers it as **Update ready** in the
sidebar above Settings.

### What's new

- **Playback fix.** Current Silo server versions refused original-file streams (HTTP 403). Silage now sends its sign-in with them, as Silo's own apps do, and keeps it fresh during long films.
- **Skip with one press.** While Skip Intro, Skip Recap or Skip Credits is on screen, Enter presses it.
- **3× speed** in the player's Speed menu.
- **Ctrl+Q** quits Silage from anywhere. Esc on the home page still asks first.
- **Exclusive audio on Windows.** Settings → Playback → Exclusive audio gives Silage the sound device to itself during playback, so Windows doesn't mix or resample it.
- **Linux sign-in back in your keyring.** Since 0.2.0 a library conflict had quietly disabled the desktop keyring, so Remember me kept sign-ins in an owner-only file instead. Silage now saves them in KDE Wallet or GNOME Keyring again and moves an existing sign-in there automatically. Settings → About shows where it's saved.
- The experimental Dolby Vision setting now appears only on hardware it supports.

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
compositor is needed. See [setup and validation status](https://github.com/evman/silage/blob/v@VERSION@/DOLBY_VISION.md).

Linux retains native Wayland/Vulkan HDR playback. Windows currently tone-maps HDR
to SDR. Server features depend on your Silo server's capabilities and profile
permissions. Watch Together, TV sign-in and offline downloads are not included.

### Windows 10/11 x64

[Download Silage-Setup.exe](https://github.com/evman/silage/releases/download/v@VERSION@/Silage-Setup.exe) and run it. It installs per user with no
administrator access and no wizard, then starts Silage. No Java or mpv installation
is needed. The portable EXE and ZIP are no longer published: install once, sign in
again, and delete the old portable folder.

The Windows build is unsigned, so SmartScreen may show a first-run warning.
See [Windows instructions](https://github.com/evman/silage/blob/v@VERSION@/WINDOWS.md).

### Linux

**Automatic installer** (adds the signed repository; run it once if you installed Silage before 0.5.0):
```sh
curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
```

To add a repository by hand (apt, dnf or pacman), see [LINUX.md](https://github.com/evman/silage/blob/v@VERSION@/LINUX.md#install).
For full H.264/HEVC support on Fedora, install FFmpeg from [RPM Fusion](https://rpmfusion.org/Howto/Multimedia).

**Other distributions:** extract `silage-linux-x86_64.tar.gz`, then run the
included `install.sh` or launch `silage/bin/silage` directly.

Linux bundles Java and needs your distribution's libmpv 0.37+. Saved settings
remain in the XDG directories; sessions use the desktop keyring when available,
otherwise an owner-only file. Run `silage --check` to verify playback dependencies.
See [Linux instructions](https://github.com/evman/silage/blob/v@VERSION@/LINUX.md).
The Linux download also includes the optional helper and private playback
libraries. Package installers include the helper's host dependencies. Users of the portable build need Python 3, pkexec/polkit, Intel GPU Tools and libdrm before enabling DV.
`silage-playback-runtime-sources.tar.gz` supplies the exact corresponding mpv and
libplacebo sources, licenses, applied patch, build instructions and runtime hashes.

Versioned and stable-named downloads contain the same builds. The signed `SHA256SUMS`
covers the Linux and Windows downloads. The attached `PKGBUILD` contains the
exact Linux tarball checksum for building the Arch package yourself.
