<p align="center">
  <img src="docs/media/splash.gif" alt="Silage startup animation" width="720">
</p>

<h1 align="center">Silage</h1>
<p align="center"><b>An unofficial Silo client for Linux and Windows</b> — a fullscreen, couch-friendly home-theater app for your <a href="https://siloserver.org">Silo</a> media server.</p>

<p align="center">
  <img src="docs/media/home.png" alt="Silage home screen" width="900">
</p>

## Features

- **Direct play, full quality.** Silage plays your files as they are, with no server transcoding, using mpv (FFmpeg + libplacebo) with hardware decoding (VA-API, NVDEC, Vulkan).
- **HDR passthrough on Linux.** On a monitor with HDR enabled (KDE Plasma), video plays in a native Wayland surface with true HDR. Other displays and the Windows build tone-map HDR content to SDR.
- **Experimental Dolby Vision on Linux.** The helper and private playback runtime are included. Enable it in Settings → Video and restart; only the display helper receives administrator authorization. Tested with Intel Arc A380 and ASUS PG32UCDM over DisplayPort, including Profile 7 MEL/FEL processing. Full Dolby tone-mapping/color accuracy remains unverified. See [setup, compatibility and limitations](DOLBY_VISION.md).
- **Great upscaling.** 1080p films on a 4K screen use mpv's high-quality profile (`ewa_lanczossharp`, debanding).
- **Choose your copy.** The best-quality copy is selected by default. When a title is in several libraries (say *Movies* and *Movies (4K)*), pick which version to play: *4K · Dolby Vision · HEVC · TrueHD* or *1080p · H.264 · E-AC3*.
- **A player HUD like Silo's web player.** Seek bar with buffered range and intro/credits markers; audio, subtitle and speed menus; playback info; Skip Intro / Skip Credits.
- **Subtitles.** Built-in tracks, server subtitle search/download and AI creation, with appearance controls and your profile's choice switched on automatically.
- **Plex-HTPC-style browsing.** A sidebar of your libraries, poster rows with a backdrop marquee, library grids, collections, movie and series pages with seasons and episodes, search, and a watchlist.
- **More ways to browse.** For You recommendations, calendar, shuffle, profile collections and cast pages, with right-click actions for favorites, watchlist and watched status.
- **Requests and notifications.** Request media, follow its progress and read your notification inbox. Background arrivals use native desktop popups that open the inbox.
- **Episode playback.** Next episode, Up next, autoplay and automatic intro skipping, plus chapters and a draggable seek bar.
- **Remember me.** Per-server saved sign-in, with Linux desktop keyring support and Windows account encryption. A single-profile account opens directly.
- **Resume anywhere.** Playback picks up where you left off; Back returns you to exactly where you were.
- **Made for a remote.** Everything works with arrows, Enter and Esc, as well as the mouse.

## Install

**Windows 10/11 (x64):** [Download Silage-Portable.exe](https://github.com/evman/silage/releases/latest/download/Silage-Portable.exe), put it in a writable folder, and double-click it. Java and mpv are included. No installer or administrator access is needed. A [ZIP alternative](https://github.com/evman/silage/releases/latest/download/Silage-Windows-x64.zip) is also available. See [WINDOWS.md](WINDOWS.md) for portable settings, saved logins and build instructions.

**Linux:** Packages below use the same app features. See [LINUX.md](LINUX.md) for desktop integration, saved logins and build notes. All development now happens on `main`.

**One command, any distro.** Detects your distro and installs the latest release (the .deb, the .rpm, or the Arch package):
```sh
curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
```

Or pick yours:

**Arch Linux**:
```sh
curl -fLO https://github.com/evman/silage/releases/latest/download/silage-bin-x86_64.pkg.tar.zst && sudo pacman -U silage-bin-x86_64.pkg.tar.zst
```
To build the Arch package yourself, download the release's [PKGBUILD](https://github.com/evman/silage/releases/latest/download/PKGBUILD). It pins the matching Linux tarball checksum and can be built with `makepkg`.

**Ubuntu 24.04+ / Debian 13**:
```sh
curl -fLO https://github.com/evman/silage/releases/latest/download/silage_amd64.deb && sudo apt install ./silage_amd64.deb
```

**Fedora 40+**:
```sh
sudo dnf install https://github.com/evman/silage/releases/latest/download/silage.x86_64.rpm
```
Fedora's stock FFmpeg leaves out H.264/HEVC; for full codec support, install FFmpeg from [RPM Fusion](https://rpmfusion.org/Howto/Multimedia).

**Any other distro** (portable build into `~/.local`):
```sh
curl -fL https://github.com/evman/silage/releases/latest/download/silage-linux-x86_64.tar.gz | tar xz && ./silage-*/install.sh
```

Linux bundles Java, a private playback runtime and the DV helper. Keep **mpv 0.37 or newer** (libmpv) from your distribution installed: it is the fallback when the bundled renderer's native dependencies are incompatible. Packages declare the helper's Python/polkit/Intel GPU Tools dependencies. Run `silage --check` to check the selected runtime. On first launch, enter your Silo server's address, sign in, and pick your profile. Experimental DV is disabled by default; ordinary playback does not ask for administrator authorization.

### Build from source

On Linux, needs JDK 21 and mpv:
```sh
git clone https://github.com/evman/silage.git
cd silage
./gradlew :app:run
```
`scripts/package.sh <version>` builds the Linux release packages (needs [nfpm](https://nfpm.goreleaser.com)). On Windows, use `package-windows.cmd -PappVersion=<version>` after preparing the dependencies in [WINDOWS.md](WINDOWS.md). See [CONTRIBUTING.md](CONTRIBUTING.md) for the shared development and release workflow.

## Controls

| | Browsing | Playing |
|---|---|---|
| Arrows | Move around | ←/→ skip 10 s (HUD hidden); move between controls (HUD shown) |
| Enter | Open / select | Show the HUD / press the focused control |
| Space | — | Play / pause |
| I | — | Playback info |
| Esc | Back, then confirm quitting at the root | Close an open menu; otherwise leave the player |
| Backspace | Back | Close a menu, hide the HUD, then leave the player |
| Media keys | — | Play/pause, stop, next/forward, previous/back |
| Ctrl + / Ctrl − / Ctrl 0 | Interface size | — |

## Settings

Settings → Interface controls **Size** and **Sidebar** libraries/order. Settings → Video controls **HDR**, autoplay, intro skipping and preferred copies. Settings → Subtitles controls subtitle appearance. Startup fills the screen and shows the loading animation for at least four seconds before fading.
The intro blocks input. Selecting a file version returns keyboard focus to Play.
Linux's experimental Dolby Vision setting takes effect after restarting the app.

See the [changelog](CHANGELOG.md) for release changes.

## Logs

On Linux, playback logs and unexpected errors are written to `~/.local/state/silage/` (or `$XDG_STATE_HOME/silage`). On Windows, they are in `userdata/State` beside the portable EXE. Settings → About shows the log folder. Include relevant logs when reporting a problem.

## Credits and license

Silage is free software under the **GNU Affero General Public License v3.0 or later** (see [LICENSE](LICENSE)).
Its `shared` module is vendored from Silo's open-source Android client, [silo-android](https://github.com/Silo-Server), also AGPL-3.0.

Silage is **not affiliated with or endorsed by** the Silo project. *Silo* and the Silo logo are trademarks of Silo Media L.L.C.
