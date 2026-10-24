# Silage for Windows (x64)

A portable Windows build of Silage, a client for a [Silo](https://siloserver.org)
media server. Linux and Windows are maintained together on `main` and released
together, starting with Silage 0.2.0.

## Install and run

Silage for Windows is portable: no installer, Java installation, mpv installation
or admin rights are needed. Windows 10/11 is required.

**Single EXE:** [Download Silage-Portable.exe](https://github.com/evman/silage/releases/latest/download/Silage-Portable.exe), put it in a writable folder and double-click it.
It contains the app, Java and mpv. On first launch it automatically unpacks them
into `.silage-runtime` beside itself; later launches reuse those files. Settings
stay in `userdata` beside the EXE, outside the unpacked runtime. The small
launcher uses the .NET Framework included in Windows.

**[ZIP alternative](https://github.com/evman/silage/releases/latest/download/Silage-Windows-x64.zip):**

1. Extract `Silage-Windows-x64.zip` anywhere you like (a folder in Documents,
   another drive, a USB stick).
2. Open the extracted `Silage` folder and run `silage.exe`.

Keep the whole `Silage` folder together; `silage.exe` needs the `app` and
`runtime` folders and `libmpv-2.dll` beside it. Java and libmpv are bundled, so
nothing else has to be installed and nothing is added to PATH.

From a terminal, `Silage-Portable.exe --check` confirms the bundled mpv loads
and `Silage-Portable.exe --version` prints the version. In the ZIP alternative,
use `silage.exe` for those commands.

The build is not code-signed, so Windows SmartScreen may warn the first time it
runs ("More info" → "Run anyway").

## Where Silage keeps its files

Settings, saved sign-in, logs and caches live in `userdata` beside the EXE:

| Folder | Contents |
|---|---|
| `Config` | device id, interface settings (`ui.json`) |
| `Data` | saved sign-in and preferences (encrypted, see below) |
| `State` | logs (`silage.log`, `last-playback.json`, and `playback-error.log` after a failed playback) and the playback journal |
| `Cache` | downloaded artwork |

For the single EXE, move the EXE and `userdata` together to take settings with
you. `.silage-runtime` can be deleted while the app is closed; it will be
recreated on the next launch. A new release EXE can replace the old one while
keeping `userdata`. For the ZIP build, move or copy the whole `Silage` folder.
Choose a writable folder, rather than Program Files: Silage does not fall back
to AppData.
To reset Silage, close it and delete `userdata`. Deleting the entire `Silage`
folder (or the EXE, `userdata` and `.silage-runtime`) removes the app and its saved data.
Settings → About shows the log folder; include those logs when reporting a problem.

To keep settings from an earlier build, close Silage and copy the contents of
`%LOCALAPPDATA%\Silage` (the `Config`, `Data`, `State` and `Cache` folders) into
`userdata` beside the EXE before launching it. Very early builds used
`%USERPROFILE%\.config\silage`, `.local\share\silage`, `.local\state\silage`
and `.cache\silage`; copy their contents into `userdata\Config`, `Data`,
`State` and `Cache`, respectively. Existing profile folders are left alone;
a fresh portable copy never imports settings automatically.

**Remember me** (on the sign-in screen and in Settings → Account) is off by
default. When on, the session is encrypted with Windows DPAPI for your Windows
user account; your password is never stored. A copy of the folder on another
PC or Windows account requires signing in again; interface settings still travel.

## What works

- The animated startup logo stays visible for at least four seconds, and
  longer when the app is still loading, before fading into the interface.
- Connect, sign in, profiles (with PINs; with a single profile Silage skips
  "Who's watching?" and only asks for its PIN if it has one), Home,
  libraries, search and title pages, with resume and choice of version.
- Library pages sort by everything the Silo web app offers (date added, title,
  release date, year, duration, ratings, resolution, bitrate, progress and
  more, ascending or descending). **Filters** narrows them by watch status,
  genre, decade, duration, resolution, HDR / Dolby Vision, content rating,
  audio, subtitle and original language, studio, network and country.
- Collections: the sidebar's Collections page lists every collection you
  can see, with a pill per collection group; libraries also have their own
  Collections tab.
- Playback inside the Silage window (Direct3D 11, hardware decoding), with a
  player bar for audio, subtitles, speed, chapters, playback info and fullscreen.
- Skip Intro / Skip Recap / Skip Credits, Next Episode during the credits, an
  "Up next" screen with an autoplay countdown. Settings → Playback can skip
  intros, recaps and credits automatically, each on its own switch.
- Settings → Playback also chooses the audio language new videos start with
  and the sound output (speakers, headphones, a TV over HDMI). Settings →
  Subtitles chooses whether subtitles start off, on or forced-only, and in
  which language. Unset, both follow your Silo profile.
- Video settings during playback: Fit/Fill, aspect ratio and zoom.
- Watchlist, favorites and watched / unwatched (titles and episodes). Right-click
  a poster to play, resume or start over, add to or remove from the watchlist,
  mark watched, reset progress, or remove it from Continue Watching / Next Up.
- Cast pages, For You recommendations, the Calendar and Shuffle (when the
  server offers them).
- Requests (when the server has them turned on for your profile): Search
  shows titles that aren't in the library yet under *Not in your library*;
  a title's page has a Request button and shows how the request is going,
  and the Requests page in the sidebar lists your requests and suggestions.
- Subtitles from the player: the Subtitles menu has *Find subtitles…*,
  which searches the server's subtitle providers in your profile's subtitle
  language (and English), and, when the server has AI subtitles turned on,
  *Create subtitles with AI…*, which transcribes the audio (and translates
  it into your language if that differs). The chosen subtitles switch on by
  themselves; the video restarts where it was, which takes a moment.
- Favorites and History: sidebar pages beside Watchlist, as poster grids of the
  titles you marked as favorites and of what you watched lately.
- Trailers & More: title pages show a rail of the title's trailers and extras, all
  played inside Silage. Extras your server holds (featurettes, behind the scenes) play
  directly. YouTube trailers use yt-dlp and Deno, which the packaged app carries in its
  `tools` folder (official release builds, checked against the checksums published with them
  when the app is built). A copy downloaded into `userdata\Data\tools` takes precedence, and a
  build without the folder asks once to download them. yt-dlp is its folder build, which
  starts faster than the single-file one. A trailer plays at the best quality YouTube has,
  at any resolution. Silage looks up a trailer's streams as soon as its title page opens
  (and when you point at a trailer), so pressing play starts it almost at once.
- More like this: movie and show pages end with a row of similar titles from your server
  (the same ones Silo's web client shows), under the cast. Click one to open its page.
- Back: pages that sit on top of another (a title, a person, a collection) have a
  **Back** button at the top left; Esc, Backspace, Alt+Left and the mouse's Back
  button do the same. Clicking the dimmed page behind a menu (the version list, a
  player menu) closes it.
- Notifications: the bell in the sidebar shows how many are unread and opens
  the inbox (new episodes, fulfilled requests). While Silage is in the
  background, new ones also pop up as Windows notifications; clicking one
  brings Silage forward on the inbox.
- Opens as a normal window, maximized or where you left it, with a dark title bar and
  the taskbar visible. **F11** (or the player's fullscreen button) fills the monitor with a
  borderless window and back; it is never exclusive fullscreen, so Alt+Tab and other
  windows behave normally. *Settings → Interface → Window → Fullscreen* opens Silage
  borderless every time instead. Fullscreen refits to the monitor it is on when moved
  there (Win+Shift+Arrow). Escape goes back through pages, then offers **Quit Silage?**
  when there is no previous page. **Cancel** is focused first; use arrows or Tab to
  choose, Enter/Space to activate, or click either button. Escape cancels the
  dialog. Alt+F4 closes Silage. The Silage icon is embedded in both the portable EXE
  and the app launcher.

## Controls

| Key | Browsing | Playing |
|---|---|---|
| Arrows | move around | ←/→ skip 10 s (bar hidden); move between controls (bar shown) |
| Enter | open / select | show the bar / press the focused control |
| Esc | back | close an open menu; otherwise leave the player |
| Backspace | back | close a menu, hide the bar, then leave |
| Alt+← | back | — |
| Space | — | play / pause |
| F | — | fullscreen |
| F11 | borderless fullscreen on/off | — |
| I | — | playback info |
| Ctrl+F | search | — |
| Ctrl + / Ctrl − / Ctrl 0 | interface size bigger / smaller / Medium | — |
| Media keys | — | Play/Pause, Stop, Next (next episode, else +30 s), Previous (−10 s) |

With the mouse:

- Rows of posters show ‹ › buttons at their edges while the pointer is over
  them, and long pages have a scrollbar.
- Right-click a poster for Play (Resume), Play from beginning, Open, Add to /
  Remove from watchlist, Mark as watched / unwatched and Reset progress; on
  Home also Remove from Continue Watching (or Next Up).
- The mouse's Back button goes back (except while playing).
- In the player, drag the dot on the seek bar (or click anywhere on it); the
  time under the pointer shows above the bar. Double-click the picture to
  switch fullscreen.

The keyboard's media keys work while a video is open and are given back to
other apps (music players) when it closes; a key another app is already
holding isn't available to Silage.

## Settings

- **Settings → Video:** HDR mode, *Next episode* (play automatically after a
  10-second countdown, or ask), *Intros* (skip automatically, or show a
  Skip button) and *Preferred version*: when a title has several copies
  (4K and 1080p, say) and none is picked with the version button, play the
  best one (default) or the one played last. Playback Info shows which copy
  is playing and whether it is the original file. These are stored on this PC in `ui.json`, not on your Silo
  profile.
- **Settings → Subtitles:** size, background (shadow, outline, dark box or
  none), text color and position, with a preview. They apply from the next
  video played and are stored in `ui.json`. Styled (ASS) subtitles keep their
  own look apart from the size.
- **Settings → Posters** and **Settings → Title pages:** the profile settings above. Unlike
  the PC-only settings, these are stored on your Silo profile, so they match the web app. Theme
  music, the one other Title Pages setting, isn't supported yet.
- **Settings → Interface:** *Size* (Small, Medium or Large; Large is the
  original TV-style size), and which libraries appear in the sidebar, and
  their order.

## Known limitations

- No HDR passthrough on Windows: HDR video is tone-mapped to SDR, including
  with the HDR setting on *Always*.
- The interface is laid out for a 1080p screen and scales with the window
  height, so small windows have small text (Settings → Interface → Size
  makes it bigger or smaller).
- Dolby Vision source decoding depends on the bundled Windows mpv renderer.
  Profile 5 has no HDR10 base-layer fallback; mpv processes its Dolby color
  representation and tone-maps to SDR. Profile 7 playback ignores the enhancement
  layer in this Windows runtime. The experimental Linux renderer/helper does not
  add Windows DV output.
- Mostly tested on a single monitor; multi-monitor and mixed-DPI setups have
  had less testing.
- Watch Together, TV sign-in and offline downloads are not available yet.

## Building from source

Windows x64. The build uses a portable JDK and the mpv DLL from two folders
that are not in git:

- `toolchains\jdk-*`: unpack a JDK 21 ZIP here (Microsoft OpenJDK or Temurin).
- `native\`: `libmpv-2.dll` from the mpv development archive, with
  `LICENSE-mpv-GPL.txt` and `Copyright-mpv.txt`, plus the fallback loader
  prepared by `scripts\prepare-windows.ps1`.

Download links and checksums are under *Download provenance* below. To prepare
mpv automatically, run `powershell -ExecutionPolicy Bypass -File scripts\prepare-windows.ps1`.
It verifies the mpv and Vulkan loader archive, DLL and license hashes. If `JAVA_HOME` points to JDK 21,
it also links that JDK under `toolchains` (otherwise unpack a portable JDK there).
7-Zip is needed for this preparation step. Released apps need none of these tools.

| Script | What it does |
|---|---|
| `test-windows.cmd` | runs the app, player and shared test suites; output in `test-windows.log` |
| `build-windows.cmd <tasks>` | runs any Gradle task with the portable JDK, e.g. `build-windows.cmd :app:run` |
| `package-windows.cmd -PappVersion=0.3.0` | builds `dist\Silage-Portable.exe`, plus the folder and ZIP alternatives, checks bundled mpv, and keeps the previous build in `dist\backups` |

`build-windows.py` and `package-windows.py -PappVersion=0.3.0` do the same with Python. The version flag is forwarded to Gradle, so the app and release metadata agree.
Both packaging scripts preserve `dist\Silage\userdata` when rebuilding and
back up the previous build. Release ZIPs exclude personal data. When updating
an extracted app, close it and replace the app files while keeping `userdata`.

The single EXE launcher is compiled using Windows' built-in .NET Framework C#
compiler; no separate SDK is required to package it. It embeds the clean ZIP,
unpacks a versioned runtime cache on first launch, and passes the outer EXE's
directory to the app through `SILAGE_PORTABLE_HOME`.

When running from source (`:app:run`), Windows uses `userdata` in the development
working directory. Packaged builds always use the executable's directory,
regardless of the terminal directory or shortcut's *Start in* setting. Explicit
`XDG_*_HOME` environment overrides remain available for isolated developer tests.

Optional native playback tests run only when these variables point at test
clips you make with ffmpeg (paths relative to the repository are fine):

- `SILAGE_SMOKE_CLIP`: a short H.264/AAC video, e.g.
  `ffmpeg -f lavfi -i testsrc=size=640x360:rate=24 -f lavfi -i sine -t 20 -c:v libx264 -c:a aac smoke.mp4`
- `SILAGE_AUDIO_SMOKE_CLIP`: a video with three audio tracks, for the audio menu.

## Release automation

Pushing a version tag from `main` runs the Linux and Windows build/test jobs.
The Windows job prepares checksum-pinned mpv dependencies and publishes the
single EXE and clean ZIP, with versioned copies and SHA256 checksums. A separate
Windows job downloads the published EXE and verifies extraction and playback.
The build remains unsigned; this release does not add a code-signing certificate.

## How the Windows build works

- jpackage app image with a bundled Java 21 runtime; libmpv is loaded through
  JNA as `libmpv-2.dll`, with the C runtime's `LC_NUMERIC` set for mpv.
- Video renders into the window's own native handle (HWND) with mpv's
  `gpu-next` on Direct3D 11 and `hwdec=auto-safe`; Wayland/VA-API options are
  Linux-only.
- The window starts borderless at the full bounds of its monitor, using a normal
  Win32 window rather than AWT exclusive fullscreen (which minimizes when focus
  leaves). Existing saved window bounds only choose the monitor; they cannot
  restore a decorated or taskbar-sized window. Playback fullscreen restores this
  borderless app window when leaving playback.
- Session and preference files are written atomically with an owner-only NTFS
  ACL; the remembered session is DPAPI-encrypted (current user) and fails
  closed if it can't be decrypted.
- Watchlist, favorites and watched use Silo's v2 endpoints directly; upstream's
  Android-only offline outbox is not used on desktop.
- Device metadata reports Windows.
- mpv imports the Vulkan loader even during D3D11 playback. The app prefers the
  system loader, with a bundled fallback under `fallback` for clean machines.
  This needs no Vulkan SDK, runtime installer, or administrator rights.

## Download provenance

Microsoft OpenJDK 21 ZIP: https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip
SHA256: 192441a9d27da813bada974bb88b4cf64d37a9589ed37f204374d411ca5ce07f

mpv development archive (generic x86_64, not x86_64-v3):
https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261016/mpv-dev-x86_64-20261016-git-a2cd83c56d.7z
SHA256: 0142058a8d20930f1c086d0c16a593f9e27a8b3fe9d1f4bb3b2ee4a2d5ec994b
`libmpv-2.dll` SHA256: 0fc3d4a64453fc65b801a687cede8196899788a81a4cd082a9ea477fc418b10f
Build scripts: https://github.com/shinchiro/mpv-winbuild-cmake
mpv source: https://github.com/mpv-player/mpv/tree/a2cd83c56d

mpv's GPL license and copyright notice are shipped beside `silage.exe`.

Vulkan loader fallback (x64), with its runtime license included:
https://sdk.lunarg.com/sdk/download/1.4.341.0/windows/VulkanRT-X64-1.4.341.0-Components.zip
Archive SHA256: 20571dcc8292b80a47d68bbbfa4094d87044041f50f4e2e1f326a01e3732b226
`vulkan-1.dll` SHA256: 98bc6e33b384f83e8cc36174ce1ba81357611d0cbe03462e1ca58d0663034039
