# Linux desktop build

Linux and Windows use the same app, player HUD and server feature code. The
Linux packages in 0.2.0 include every shared feature developed during the
Windows port, plus the later startup and navigation changes. Both platforms
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
- Installed Linux builds use the XDG config/data/state/cache directories. Saved
  sessions use Secret Service (KWallet/GNOME Keyring) when available, otherwise
  an atomic owner-only file (0600). Windows uses its portable `userdata` folder
  and Windows account encryption instead.
- Remember me is per server. Turning it off removes saved authentication while
  keeping the current in-memory sign-in. Previously saved Linux sign-ins migrate
  with Remember me enabled; an explicit opt-out is respected. Passwords are not
  saved. The keyring/file fallback behavior is unchanged.

## Build and verify

JDK 21 and libmpv 0.37+ are required to build/test. Use Temurin 21 for release packaging (as in CI); set `SILAGE_PACKAGING_JAVA_HOME` to its JDK directory when your build toolchain uses another vendor. Java is bundled in packages;
libmpv and graphics drivers come from the Linux distribution.

```sh
./gradlew :app:test :player:test :shared:desktopTest
scripts/package.sh 0.2.0 /path/to/nfpm
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
