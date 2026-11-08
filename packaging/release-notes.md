## Silage @VERSION@ — Linux and Windows

The biggest Silage release yet: downloads, Watch Together, game controllers and
profile management. If you have 0.5.0 or later, Silage offers it as **Update ready**
in the sidebar above Settings.

### What's new

- **Downloads.** Save a title, an episode or a whole season from its page and watch with no connection, subtitles included. Stopped downloads pick up where they left off, and where you got to is sent to the server once it can be reached.
- **Watch Together.** Host or join a party with a code or a `silo://` link, choose or vote on a title, and watch in sync. Hosts choose who can play and pause.
- **Game controllers.** Xbox-style pads and DualSense / DualShock 4 work on every page and in the player, on Windows and Linux, with an on-screen keyboard (press A on any text box).
- **Profiles.** Add, edit and delete profiles from "Who's watching?" or Settings → Account: PINs, kids profiles, rating limits and library limits.
- **A better player.** Picture previews and chapter names above the seek bar, marked intros and credits, a lighter player bar with grouped buttons, subtitle styling while you watch, and upload a subtitle file from the Subtitles menu.
- **Smarter 4K.** A 4K video that keeps stopping to buffer can switch to its 1080p version at the same moment, with no converting, and back again (Settings → Video).
- **Your library, your way.** Star ratings, an A–Z bar for title-sorted libraries, editable Home rows, your taste on For You, and audio, subtitles and auto-skip remembered for each show.
- **Windows:** what's playing in the Windows media overlay, and play controls under the taskbar thumbnail.
- **Linux:** F11 now leaves fullscreen properly on KDE.
- **Support Silage.** A Sponsor button and progress bar towards the Apple Developer and Google Play accounts needed to bring Silage to macOS, iPhone, iPad, Apple TV and Android.

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
permissions. TV sign-in is not included.

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
