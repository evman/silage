# Silage for Windows (x64)

A portable Windows build of Silage, a client for a [Silo](https://siloserver.org)
media server. This port is developed on the `windows-port` branch and is not an
official release yet.

## Install and run

Silage for Windows is portable: there is no installer and no admin rights are
needed.

1. Extract `Silage-Windows-x64.zip` anywhere you like (a folder in Documents,
   another drive, a USB stick).
2. Open the extracted `Silage` folder and run `silage.exe`.

Keep the whole `Silage` folder together; `silage.exe` needs the `app` and
`runtime` folders and `libmpv-2.dll` beside it. Java and libmpv are bundled, so
nothing else has to be installed and nothing is added to PATH.

From a terminal in that folder, `silage.exe --check` confirms the bundled mpv
loads and `silage.exe --version` prints the version.

The build is not code-signed, so Windows SmartScreen may warn the first time it
runs ("More info" → "Run anyway").

## Where Silage keeps its files

Nothing is written into the extracted folder. Settings, the sign-in and caches
live in `%LOCALAPPDATA%\Silage`:

| Folder | Contents |
|---|---|
| `Config` | device id, interface settings (`ui.json`) |
| `Data` | saved sign-in and preferences (encrypted, see below) |
| `State` | logs (`silage.log`, `last-playback.json`, and `playback-error.log` after a failed playback) and the playback journal |
| `Cache` | downloaded artwork |

Moving or deleting the extracted `Silage` folder doesn't affect them. To reset
Silage completely, close it and delete `%LOCALAPPDATA%\Silage`. Settings →
About shows the log folder; include those logs when reporting a problem.

Older test builds kept these files in `.config`, `.local` and `.cache` folders
in your user profile; the first launch moves them into `%LOCALAPPDATA%\Silage`.

**Remember me** (on the sign-in screen and in Settings → Account) is off by
default. When on, the session is encrypted with Windows DPAPI for your Windows
user account; your password is never stored. A copy of the folder on another
PC or Windows account starts signed out.

## What works

- Connect, sign in, profiles (with PINs; with a single profile Silage skips
  "Who's watching?" and only asks for its PIN if it has one), Home,
  libraries, search and title pages, with resume and choice of version.
- Collections: the sidebar's Collections page lists every collection you
  can see, with a pill per collection group; libraries also have their own
  Collections tab.
- Playback inside the Silage window (Direct3D 11, hardware decoding), with a
  player bar for audio, subtitles, speed, chapters, playback info and fullscreen.
- Skip Intro / Skip Credits, Next Episode during the credits, an "Up next"
  screen with an autoplay countdown, and optional automatic intro skipping.
- Video settings during playback: Fit/Fill, aspect ratio and zoom.
- Watchlist, favorites and watched / unwatched (titles and episodes).
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
- Notifications: the bell in the sidebar shows how many are unread and opens
  the inbox (new episodes, fulfilled requests). While Silage is in the
  background, new ones also pop up as Windows notifications; clicking one
  brings Silage forward on the inbox.
- Dark title bar, and the window reopens where it was left.

## Controls

| Key | Browsing | Playing |
|---|---|---|
| Arrows | move around | ←/→ skip 10 s (bar hidden); move between controls (bar shown) |
| Enter | open / select | show the bar / press the focused control |
| Esc, Backspace | back | leave fullscreen, close a menu, hide the bar, then leave |
| Alt+← | back | — |
| Space | — | play / pause |
| F | — | fullscreen |
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
- **Settings → Interface:** *Size* (Small, Medium or Large; Large is the
  original TV-style size), and which libraries appear in the sidebar, and
  their order.

## Known limitations

- No HDR passthrough on Windows: HDR video is tone-mapped to SDR, including
  with the HDR setting on *Always*.
- The interface is laid out for a 1080p screen and scales with the window
  height, so small windows have small text (Settings → Interface → Size
  makes it bigger or smaller).
- Dolby Vision plays from its HDR10 base layer with the Dolby Vision
  metadata applied by mpv (profiles 5, 7 and 8, including UHD Blu-ray
  remuxes); the profile 7 enhancement layer is ignored.
- Mostly tested on a single monitor; multi-monitor and mixed-DPI setups have
  had less testing.
- Watch Together, TV sign-in and offline downloads are not available yet.

## Building from source

Windows x64. The build uses a portable JDK and the mpv DLL from two folders
that are not in git:

- `toolchains\jdk-*`: unpack a Microsoft OpenJDK 21 ZIP here.
- `native\`: `libmpv-2.dll` from the mpv development archive, with
  `LICENSE-mpv-GPL.txt` and `Copyright-mpv.txt`.

Download links and checksums are under *Download provenance* below.

| Script | What it does |
|---|---|
| `test-windows.cmd` | runs the app, player and shared test suites; output in `test-windows.log` |
| `build-windows.cmd <tasks>` | runs any Gradle task with the portable JDK, e.g. `build-windows.cmd :app:run` |
| `package-windows.cmd` | builds `dist\Silage` and `dist\Silage-Windows-x64.zip`, checks the bundled mpv loads, and keeps the previous build in `dist\backups` |

`build-windows.py` and `package-windows.py` do the same with Python.

Optional native playback tests run only when these variables point at test
clips you make with ffmpeg (paths relative to the repository are fine):

- `SILAGE_SMOKE_CLIP`: a short H.264/AAC video, e.g.
  `ffmpeg -f lavfi -i testsrc=size=640x360:rate=24 -f lavfi -i sine -t 20 -c:v libx264 -c:a aac smoke.mp4`
- `SILAGE_AUDIO_SMOKE_CLIP`: a video with three audio tracks, for the audio menu.

## How the port works

- jpackage app image with a bundled Java 21 runtime; libmpv is loaded through
  JNA as `libmpv-2.dll`, with the C runtime's `LC_NUMERIC` set for mpv.
- Video renders into the window's own native handle (HWND) with mpv's
  `gpu-next` on Direct3D 11 and `hwdec=auto-safe`; Wayland/VA-API options are
  Linux-only.
- The window starts maximized rather than in AWT exclusive fullscreen (which
  minimizes when focus leaves). Playback fullscreen is borderless on the
  window's current monitor and restores the previous size and position.
- Session and preference files are written atomically with an owner-only NTFS
  ACL; the remembered session is DPAPI-encrypted (current user) and fails
  closed if it can't be decrypted.
- Watchlist, favorites and watched use Silo's v2 endpoints directly; upstream's
  Android-only offline outbox is not used on desktop.
- Device metadata reports Windows.

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
