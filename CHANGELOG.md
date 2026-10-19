# Changelog

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
