## Silage @VERSION@ — Linux and Windows

Linux and Windows now ship together from `main`, with the same browsing and
playback features.

### What's new

- Windows portable app: one EXE containing Silage, Java and mpv, plus a ZIP alternative. Settings stay in `userdata` beside the EXE; saved sign-in uses Windows account encryption.
- Linux feature parity, with native MPRIS media controls and clickable desktop notifications. Remember me now controls saved sign-in per server; existing Linux logins migrate without being lost.
- For You, calendar, shuffle, collections and profile collections, cast pages, and right-click actions for favorites, watchlist and watched status.
- Media requests, a realtime notification inbox, subtitle search/download and server AI subtitle creation with appearance controls.
- Next episode, Up next, autoplay, automatic intro skipping, chapters and a draggable seek bar with corrected duration. Best-quality copies are preferred by default.
- Converted-stream authentication renewal, audio/source switching, Dolby Vision profile 7 direct playback and redacted playback failure logs.
- Fullscreen startup with the Silage icon, a four-second minimum startup animation, interface size controls, and Escape-to-back followed by mouse/keyboard quit confirmation.

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

Versioned and stable-named downloads contain the same builds. `SHA256SUMS`
covers the Linux and Windows downloads. The attached `PKGBUILD` contains the
exact Linux tarball checksum for building the Arch package yourself.
