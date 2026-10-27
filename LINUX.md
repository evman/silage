# Linux desktop build

Linux and Windows use the same app, player HUD and server feature code. The
Linux packages in 0.2.0 include every shared feature developed during the
Windows port. Version 0.3.0 adds input/focus/Escape fixes and the bundled experimental
Dolby tooling described below. Both platforms
are maintained on `main`; no separate UI implementation or cherry-picking is needed.

## Feature parity audit

| Area | Included in Linux |
| --- | --- |
| Browsing | For You, calendar, shuffle, collections (including profile collections in the sidebar), cast/person pages, clickable row arrows, right-click actions |
| Accounts | Skip profile selection when only one profile exists; per-server Remember me; sign-out and account replacement |
| Personal library | Watchlist, favorites, watched/unwatched actions |
| Requests and notifications | Requests pages, permissions, notification inbox, realtime arrivals and clickable native desktop notifications |
| Player | Draggable seek bar with corrected source duration, buffered range, chapters, intro/credits actions, media keys, double-click fullscreen |
| Episodes | Next episode, Up next, autoplay, automatic intro skipping |
| Subtitles | Find/download subtitles, server AI subtitle creation and progress, subtitle appearance controls |
| Playback | Highest-quality copy by default, audio/source switching, direct Dolby Vision profile 7, converted HLS authentication renewal, redacted failure logs |
| Interface | Interface size/zoom and sidebar settings, custom Silage icon, fullscreen startup, four-second minimum splash followed by its fade |
| Navigation | Escape closes child menus, goes back through pages, then offers a mouse/keyboard quit confirmation; Cancel focused by default |

Server-dependent features still require the corresponding Silo server capability
and account/profile permissions, exactly as on Windows.

## Linux desktop behavior

- Media keys use MPRIS on the session D-Bus, including Wayland. Silage advertises
  its player only during playback and releases it when playback closes. The
  desktop chooses which active player receives hardware media keys. Play/Pause,
  Stop, Next and Previous match the Windows HUD actions; Next advances an episode
  when available, otherwise skips forward, and Previous skips back.
- Notifications use `org.freedesktop.Notifications`, with the Silage icon and an
  Open inbox action. They do not depend on Java's X11 tray support. Desktop policy
  controls whether popups appear. The in-app inbox works without a notification
  daemon or session bus.
- Linux keeps native Wayland/Vulkan HDR output and KDE monitor detection, with
  embedded SDR playback and an X11 fallback. Windows retains its D3D11 backend.
  KDE custom CVT modes (including 4K/240 Hz) are supported by HDR Auto detection.
  Native playback forwards mouse press/release events for clicks and seek-bar
  dragging, and coalesces mouse motion before updating the HUD.
- Installed Linux builds use the XDG config/data/state/cache directories. Saved
  sessions use Secret Service (KWallet/GNOME Keyring) when available, otherwise
  an atomic owner-only file (0600). Windows keeps it in `%APPDATA%\Silage` (settings in `%LOCALAPPDATA%\Silage`)
  and uses Windows account encryption instead.
- Remember me is per server. Turning it off removes saved authentication while
  keeping the current in-memory sign-in. Previously saved Linux sign-ins migrate
  with Remember me enabled; an explicit opt-out is respected. Passwords are not
  saved. The keyring/file fallback behavior is unchanged.

## Build and verify

JDK 21 and libmpv 0.37+ are required to build/test. Use Temurin 21 for release packaging (as in CI); set `SILAGE_PACKAGING_JAVA_HOME` to its JDK directory when your build toolchain uses another vendor. Java is bundled in packages;
libmpv and graphics drivers come from the Linux distribution.

```sh
./gradlew :app:test :player:test :shared:desktopTest
scripts/package.sh 0.3.0 /path/to/nfpm
```

The script creates a portable directory tarball, Debian package and RPM in
`dist/`, preserving existing Windows artifacts and older Linux builds. The
portable directory contains its Java runtime; launch `silage/bin/silage` after
extracting, or run the included `install.sh` for a per-user install. The Arch
PKGBUILD consumes the same tarball. The release workflow builds and tests all
Linux formats alongside the Windows EXE and ZIP, then publishes them together.
Local packaging commands do not publish a release. Release downloads include
a generated Arch `PKGBUILD` with the exact tarball checksum.

After installing, run `silage --version` and `silage --check`. UI tests require a
working display; desktop integration tests start a private `dbus-daemon` and
never send test notifications to your desktop. Windows DPAPI tests run natively
on Windows; the Linux restart tests cover Linux consent and file permissions.

For an optional native-window input smoke test, set
`SILAGE_NATIVE_HUD_TEST_CLIP` to a local video and run
`./gradlew :app:test --tests '*NativeHudInputTest'`. Set
`SILAGE_NATIVE_HUD_TEST_SCREEN=DP-3` (using your HDR output's connector name)
to additionally test fullscreen HDR on that display. This opens a temporary
playback window and checks keyboard input, mouse clicks and seek-bar dragging.

During playback, press **I** to inspect the source and rendered output. The
PQ transfer function is shown as **HDR (PQ…)**, since PQ alone does not identify
HDR10 versus Dolby Vision. **Source format** shows the Dolby Vision profile when
mpv reports one. **HDR passthrough** describes HDR rendering; it does not confirm
native Dolby Vision signaling to the monitor.

## Experimental native Dolby Vision milestone

The 0.3.0 Linux download includes the optional [DV helper](scripts/dv-helper/README.md)
and private renderer for the stock KDE desktop. Enable **Settings → Video →
Experimental Dolby Vision** and restart; see the [setup guide](DOLBY_VISION.md).
No separate helper download or compositor build is needed. KDE requires
administrator authorization once when this opt-in app opens. Silage
stays unprivileged; the helper performs the experimental GPU register writes.
KWin and desktop settings are not replaced or modified.
Authorization is reused between movies; canceling it keeps ordinary HDR and
does not interrupt later playback with another prompt. Closing playback restores
ordinary HDR, and closing the app ends the helper. No video decoder or network
client runs as root.

The validated path is Intel Arc A380 / i915 to ASUS PG32UCDM over DisplayPort,
with active 4K 10-bit PQ/BT.2020 output, fullscreen Profile 5, 7 or 8 and renderer RPU
reshaping. The monitor profile is deliberately restricted. The real app client
passed a stock-desktop hardware test with zero dropped frames, signal readback,
and restoration to normal HDR when the client closed. Missing-heartbeat and
relay-crash recovery also passed hardware tests. Changed display configurations
end the signal; unknown hardware is rejected. The helper remains experimental:
register writes cannot be fully synchronized with compositor modesets.
The Profile 8.1 official sample also passed five readback checks, zero dropped
frames, and restoration after close. **I → Playback Info → DV helper** reports
signal status or the reason it is inactive. The details page names the available
DV profile when the server reports it, and **Versions** selects an exact file
even when multiple copies share the same quality label. Profiles are encoded
in each file; a title with only one copy has no alternate profile to select.

Profile 7 FEL requires actual enhancement-layer processing as well as reshaping.
Profile 7 MEL requires reshaping and decoder confirmation that residual picture
data is not needed; it must not be blocked for omitting an unnecessary GPU pass.
The release bundles a pinned private mpv/libplacebo runtime using
the [runtime build and packaging scripts](scripts/dv-helper/README.md#profile-7-private-playback-runtime).
The affected movie's Profile 7 FEL excerpt passed VAAPI playback with zero
dropped frames, helper activation, enhancement off/on release/reacquisition,
and restoration after close. **I → Playback Info → Enhancement layer** reports
the active GPU processing pass. Older runtimes retain HDR10 fallback.
The original Wolf of Wall Street MEL stream also passed signal activation,
zero dropped frames, metadata off/on release/reacquisition, and restoration on
close. Its HUD shows **MEL · no extra picture layer required**. The
private runtime leaves system libraries and KWin unchanged; the tested build
requires the tested FFmpeg ABI and is not a universal Linux binary. If it cannot
load, the app falls back to the distribution's libmpv. Package installs include
the root-owned helper and policy; portable app images include a fixed helper
setup that installs and starts only that component during startup authorization.
The UI and media/network processing never run as root.

The [DV lab](scripts/dv-lab/README.md) retains the alternative KWin 6.8.0 patch
and its separate hardware evidence. That backend uses the standard DRM metadata
API and requires `SILAGE_EXPERIMENTAL_DV=compositor` under the patched compositor
started with `KWIN_SILAGE_EXPERIMENTAL_DV=1`. Stock KWin lacks that private API.
Neither backend establishes full Dolby content-mapping/colorimetric conformance;
Linux HDMI and broader hardware coverage remain outstanding.
The current metadata mapping omits Dolby L2/L8 creative trims. A fixed captured
signaling packet and active renderer pass do not prove complete Dolby content
mapping, LLDV/TV-led transport or measured color accuracy. See the
[validation work and known limitations](DOLBY_VISION.md#what-remains-to-validate).
