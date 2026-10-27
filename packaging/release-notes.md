## Silage @VERSION@ — Linux and Windows

This release replaces the portable Windows build with a one-file installer, moves
Linux onto signed apt, dnf and pacman repositories, and lets Silage update itself.

### What's new

- **Windows installer.** `Silage-Setup.exe` installs for your user only, into `%LOCALAPPDATA%\Programs\Silage`, with no administrator access and no wizard. It adds Start menu (Windows search) and Desktop shortcuts and an entry in Settings → Apps, whose uninstall asks before deleting your settings. Settings and sign-in live in `%APPDATA%\Silage` and `%LOCALAPPDATA%\Silage`.
- **Linux repositories.** Signed apt (Debian/Ubuntu), dnf (Fedora) and pacman (Arch) repositories keep Silage up to date with your normal system updates. The one-command installer adds the repository.
- **Updates inside Silage.** Silage checks for updates in the background and shows **Update ready** in the sidebar above Settings, never during playback. Selecting it installs the update (silently on Windows; on Linux through the package manager, with the system password prompt) and restarts Silage. Settings → About has *Automatic updates*, *Check now* and *What's new*. Every update is signature-checked.

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

To add a repository by hand (apt, dnf or pacman), see the [README](https://github.com/evman/silage/blob/v@VERSION@/README.md#install).
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
