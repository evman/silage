## Silage @VERSION@ — Linux and Windows

This release adds sign-in with a code or QR code, fixes remote controls'
Back button and the pointer over playing video, and makes libraries remember
their sort and filters.

### What's new

- **Sign in with a code or QR code.** The sign-in screen shows a QR code and a short code next to the password form. Scan it or go to `<your server>/activate` on your phone or browser, sign in there (including single sign-on such as Discord) and approve. Servers that don't offer device sign-in show the password form as before.
- **Remote Back button.** The Back / Browser Back button on remotes and air mice (for example the G60S Pro), and the mouse's Back button, now go back everywhere: pages, dialogs and the player. On Windows, Browser Back sent as an app command is caught too. If your remote's Back still does nothing, please say so in issue #3.
- **Pointer over video.** The mouse pointer hides with the player controls after 3 seconds of no movement and comes back when you move the mouse.
- **Libraries remember sort and filters.** Each library keeps its sort, order and filters on this device, between visits and after restarting.

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
