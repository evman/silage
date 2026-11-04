# Changelog

## 0.5.2 — 2026-11-04

- Show Dolby Vision on any GPU, over DisplayPort or HDMI, on Arch-based systems and
  Fedora: Settings → Video → Dolby Vision through KDE installs an opt-in version of
  KDE's KWin with a small Silage patch (and can restore the standard one). It stays
  current with normal system updates. Tested on an RTX 5090 over HDMI. See
  docs/DOLBY_VISION.md.
- Use Silage in a window on Linux (issue #7): F11 switches between fullscreen and a
  window anywhere (F too while playing), and Settings → Interface → Window chooses how it
  opens. Fullscreen stays the default and the recommended way to use Silage. HDR video in a
  window takes the Silage window's place, with Silage's icon.
- Play Dolby Vision Profile 7 with hardware decoding on NVIDIA (direct NVDEC) as well
  as Intel (VAAPI).
- Wait on the first frame of a Dolby Vision title while the monitor switches, so the
  opening isn't missed and no frames drop.
- Show the Dolby Vision status in Playback Info, and copy Playback Info to the
  clipboard (C, or the Copy details button).
- Add Settings → Video → Send hardware report, to help bring Dolby Vision to more
  GPUs and monitors.
- Fix restarting after a Linux update (“Failed to exec spawn helper”).
- Restart Silage instead of freezing when the playback engine stops responding.
- Load subtitles stored on the server when the video comes from the server's proxy.
- Ctrl+Q quits during playback too.
- Write the title on posters that are missing or fail to load.
- Show download progress in the sidebar while an update downloads, retry a failed
  update check later, and clean up old update downloads.
- Fix opening links (such as the hardware report) freezing Silage on some Linux
  desktops.

## 0.5.1 — 2026-11-03

- Fix playback failing (HTTP 403) on current Silo server versions: Silage now
  sends its sign-in with original-file streams from your server, as Silo's own
  apps do, and keeps it fresh during long films (issue #4).
- Let Enter press Skip Intro, Skip Recap or Skip Credits while the button is on
  screen.
- Put the best matches first in search: shows and movies whose titles match
  lead, and episodes with the same name come last (searching "fallout" used to
  show dozens of episodes before the Fallout series).
- Add 3× playback speed (issue #6).
- Add Ctrl+Q to quit, and list quitting in the README's controls (issue #5).
- Add exclusive-mode audio on Windows (Settings → Playback → Exclusive audio).
- Save the Linux sign-in in the desktop keyring (KDE Wallet, GNOME Keyring) again.
  Since 0.2.0 a library conflict had disabled the keyring, so sign-ins were kept
  in an owner-only file instead; they move into the keyring automatically.
  Settings → About shows where the sign-in is saved, and every build now tests
  against a real keyring.
- Show the experimental Dolby Vision setting only on hardware it supports.
- Keep the profile picture steady between pages, and remember searches that
  found something.

## 0.5.0 — 2026-10-27

- Install Silage on Windows with one per-user Silage-Setup.exe: no administrator
  access and no wizard. It installs into `%LOCALAPPDATA%\Programs\Silage`, adds
  Start menu (Windows search) and Desktop shortcuts, and appears in Settings →
  Apps, whose uninstall asks before deleting your settings. Settings and sign-in
  live in `%APPDATA%\Silage` and `%LOCALAPPDATA%\Silage`. The portable EXE and ZIP
  are gone: install Silage-Setup.exe once and sign in again.
- Install Silage on Linux from signed apt (Debian/Ubuntu), dnf (Fedora) and pacman
  (Arch) repositories. The README one-liner adds the repository; updates then come
  with normal system updates. Run the one-liner once if you installed earlier.
- Update Silage from inside the app. It checks in the background and shows
  "Update ready" in the sidebar above Settings, never during playback; selecting it
  installs (silently on Windows, through the package manager with the system
  password prompt on Linux) and restarts. Settings → About has Automatic updates,
  Check now and What's new. Every update is signature-checked.

## 0.4.1 — 2026-10-27

- Add sign-in with a code or QR code next to the password form, so accounts that
  use single sign-on (such as Discord) can sign in by approving on a phone or
  browser. Hidden when the server doesn't offer device sign-in.
- Make remotes' Back / Browser Back button and the mouse's Back button go back
  on pages, in dialogs and in the player, including Browser Back app commands on
  Windows (issue #3).
- Hide the mouse pointer over playing video along with the player controls.
- Remember each library's sort, order and filters on this device.

## 0.4.0 — 2026-10-25

- Add Favorites and History pages; remove a title from History by right-clicking it.
- Add Trailers & More (YouTube trailers and the server's extras, played in Silage),
  More like this and an Info dialog with full details, cast, crew and file tracks
  to title pages. Trailers bundle yt-dlp and Deno on Windows and are looked up only
  when played.
- Add Go to show, Mark season watched and Mark watched up to here. Fix removing
  shows from Continue Watching and Next Up.
- Play Dolby Vision files directly on Windows, and keep the original file when a
  non-default audio track is chosen. Add streaming quality and maximum bitrate
  settings and a Quality menu in the player.
- Add player shortcuts (mute, volume, subtitles, audio, speed, next episode),
  audio and subtitle sync, a sleep timer and remembered volume.
- Add recent searches and a Devices page for signing other devices out.
- Open Windows as a normal window with optional fullscreen; add a Back button,
  keep the sidebar's profile between pages, resize artwork smoothly and redraw
  correctly after moving to a monitor with a different scale.

## Installer update — 2026-10-19

- Migrate the three unowned DV helper files left by portable setup or development
  when installing the Arch package. Limit overwrite permission to those exact
  regular files and preserve conflicts with files owned by another package.

## 0.3.0 — 2026-10-19

- Include the Linux DV helper, its portable first-launch setup, and a pinned
  private playback renderer in the app download. Add a Video setting for the
  experimental feature; only the helper receives administrator authorization.
- Verify Profile 5/8.1 and Profile 7 MEL/FEL processing and display signaling on
  the tested Intel Arc A380 / ASUS PG32UCDM DisplayPort setup. Keep unsupported
  configurations on ordinary HDR and report the missing eligibility condition.
- Show Dolby source profiles, actual FEL GPU processing, MEL residual status
  and helper signal status in Playback Info. Preserve exact-file version choices.
- Authorize the helper at startup and reuse it across movies, restoring ordinary
  HDR between players and on shutdown, missing heartbeat or relay failure.
- Block clicks and keyboard input during the four-second startup animation.
- Restore Play keyboard focus after version selection or picker dismissal.
- Make Escape dismiss playback menus or exit playback directly, removing the
  intermediate fullscreen-to-windowed transition. Prepare the browsing page
  before closing the native player, without waiting for server cleanup.
- Correct native HDR mouse input, fast clicks, dragging and motion handling.
- Document unsupported HDMI/other hardware, runtime compatibility/fallback,
  missing creative-trim processing and outstanding color/transport validation.

## 0.2.0 — 2026-10-18

Unified Linux and Windows releases from main; Windows portable EXE/ZIP with
Java and mpv included; shared browsing, requests, notification inbox, subtitle
tools, episode playback, chapters, seeking and saved sign-in. Linux gained
desktop media controls and clickable notifications. Windows HDR remains SDR
tone-mapped. Earlier releases retain their original GitHub release notes.
