# Silage — Design Spec

**Date:** 2026-10-11
**Status:** Draft, awaiting review
**One-liner:** Silage — a Silo client for Linux home-theater PCs.

## 1. Goal

A fullscreen, 10-foot media app for Linux that plays a Silo server's movies and
TV shows with the best picture quality Linux can deliver. It is the Linux
counterpart of the Silo tvOS app and feels like Plex HTPC: launched from the
desktop's app menu, opens fullscreen, driven by remote, gamepad, or keyboard.

It speaks Silo's native API (`/api/v2`, playback protocol v3), not the Jellyfin
compatibility layer.

**Success criteria for v1**

1. `yay -S silage` installs it on Arch; it appears in the app menu with its icon.
2. A new user can find their server, sign in with a phone (QR / code), pick a
   profile, and start a movie in under a minute.
3. A 4K HDR10 or Dolby Vision (profile 5/8) file direct-plays with HDR signaling
   on KDE Plasma 6 (Wayland), with hardware decoding and no dropped frames on
   the reference machine (RTX 5090 / Arc A380).
4. Resume points, watched state, and progress match what the Silo Apple and
   Android apps show for the same profile.
5. Every interaction works with a gamepad alone, a keyboard alone, and a mouse
   alone.

## 2. Decisions made

| Topic | Decision |
|---|---|
| Name | **Silage** (wordplay name, allowed by Silo's trademark policy without permission). Always described as "a Silo client", never as official. |
| Form factor | HTPC / 10-foot UI, normal desktop app that opens fullscreen (not a login session). |
| Language | Kotlin (JVM 21). |
| UI | Compose Multiplatform Desktop. |
| Silo logic | Reuse silo-android's `shared` Kotlin Multiplatform module, adding a `desktop` JVM target. |
| Playback | libmpv, in-process via JNA, rendering into its own native window. |
| Visual design | Silo's "Skyline" TV design system (from `silo-apple/docs/skyline-design-guide.md`). |
| License | AGPL-3.0-or-later (inherited from reused Silo code). |
| Distribution | AUR: `silage` (build from source) and `silage-bin` (prebuilt). |
| v1 scope | Movies and TV only. Music out. Watch Together in a later stage. |

## 3. Scope

### In v1 (Stage 1 — Core HTPC)

- Server discovery (LAN) and manual address entry; multiple saved servers.
- Sign-in: device pairing (QR + short code approved on another device), with
  username/password fallback.
- Profile picker, including PIN-locked profiles.
- Home, Movies, Series, Search, item detail, series/season/episode pages.
- Player with direct play, transcode fallback, track menus, skip intro/credits,
  Next Up, progress sync.
- Settings (section 8).
- AUR packaging, desktop entry, icons.

### Later stages (not in this spec)

- Stage 2: Calendar, full person and collection pages.
- Stage 3: Watch Together (Watch Party), Requests, realtime library updates,
  audiobooks.
- Not planned: music, ebooks, downloads/offline, admin screens.

## 4. Architecture

One JVM process, three Gradle modules:

```
silage/
├─ shared/   vendored silo-android `shared` + new `desktopMain` source set
├─ player/   libmpv wrapper (JNA); no networking, no UI
└─ app/      Compose Desktop UI, navigation, input, settings, player overlay
```

```
 app (Compose UI) ──uses──► shared (Silo API, auth, playback sessions)
        │                           │
        │ stream URL, track choices │ HTTPS / WebSocket
        ▼                           ▼
 player (libmpv) ──renders──► own Wayland window       Silo server
```

**Boundaries**

- `app` never makes HTTP calls; all server access goes through `shared`
  repositories.
- `player` knows nothing about Silo. It takes a URL plus start options, exposes
  state (position, duration, tracks, paused, buffering, video params) as Kotlin
  flows, and accepts commands (seek, select track, set property, overlay).
- `shared` stays as close to upstream as possible so fixes can be pulled from
  silo-android.

### 4.1 Reusing silo-android `shared`

- Vendored from silo-android commit `538aa09d00f3236066193e828f8435eb23e31713`
  (2026-10-11), recorded in `shared/UPSTREAM.md` together with a list of every
  local change.
- Add a `jvm("desktop")` target. Upstream's `commonMain` (549 files) is used
  unchanged.
- Provide `desktopMain` replacements for the 11 Android-only files:

| Android file | Desktop replacement |
|---|---|
| `SiloHttpClient.android.kt`, `SiloOkHttp.kt` | Ktor client on the OkHttp engine (the JVM build of the same engine) |
| `Dispatchers.android.kt` | `Dispatchers.IO`, Swing main dispatcher |
| `WallClock.android.kt` | `System.currentTimeMillis()` |
| `EncryptedTokenManagerImpl.kt`, `SecureSharedPrefs.kt` | Tokens in the Secret Service keyring (KWallet / GNOME Keyring) via libsecret; if no keyring is available, a `0600` file in `$XDG_DATA_HOME/silage`, with a one-time warning |
| `AndroidServerRegistry.kt` | JSON file in `$XDG_CONFIG_HOME/silage/servers.json` |
| `SharedPreferencesRecentWatchPartyStorage.kt` | JSON file (unused in v1, needed to compile) |
| `LanguagePresentation.android.kt`, `OverlayLanguageName.android.kt`, `SubtitleTrackDisplayLabel.kt` | `java.util.Locale`-based equivalents |

- If any `commonMain` file turns out to depend on Android APIs indirectly, the
  fix is a minimal `expect`/`actual` split, recorded in `UPSTREAM.md`.

### 4.2 Data locations (XDG)

| What | Where |
|---|---|
| Settings | `$XDG_CONFIG_HOME/silage/` |
| Image cache | `$XDG_CACHE_HOME/silage/images/` (size-capped, LRU) |
| Logs | `$XDG_STATE_HOME/silage/logs/` (rotated) |
| Servers and tokens (one blob, as on Android) | System keyring, service `silage`, account `session` (fallback: `$XDG_DATA_HOME/silage/session.json`, mode 0600) |

## 5. Screens and navigation

Follows the Skyline design guide. Layout is specified in 1920×1080 pixels
and scaled uniformly to the actual display size (4K is 2×).

**Visual rules:** background `#000000`, surface `#0A0A0A`, elevated `#15171C`,
text `#EDEDED` (secondary 60%, tertiary 38%), hairlines white at 12%, error
`#B00020`, rating amber `#FFC107`. No accent color. Focus = inverted (white
fill, black label) with a slight scale-up. Radii 8 / 12 / 18 / pill.

**First run:** Connect (discovered servers + manual entry) → Sign in (pairing QR
and code, or password) → Who's watching (profiles, PIN pad).

**Root screens** share a top bar: wordmark · Home · Movies · Series · Search ·
profile avatar. Library tabs appear only if the profile has that library type.

- **Home:** focus marquee (backdrop, logo/title, metadata of the focused card)
  over rows from the server: Continue Watching, Next Up, recommendations,
  Recently Added, and the rest of the server-defined Home sections.
- **Movies / Series:** pill row Browse · Collections · Genres · A–Z · Recently
  Added. Multiple libraries of one type → cascading selector on tab focus-dwell
  (250 ms), per Skyline §5.3.
- **Search:** on-screen keyboard (remote/gamepad) plus physical typing;
  results update as you type.
- **Profile menu:** switch profile, Watchlist, Favorites, History, Settings,
  switch server, sign out.

**Full-screen pages:** movie detail, series detail with season selector and
episode list, basic collection and person grids. Actions: Play/Resume, Play from
beginning, version picker, trailer, watchlist, favorite, mark watched.

**Input map**

| Action | Keyboard / remote | Gamepad | Mouse |
|---|---|---|---|
| Move focus | Arrows | D-pad / left stick | Hover |
| Select | Enter | A | Left click |
| Back | Esc, Backspace, remote Back | B | Right click / mouse back button |
| Context menu | Menu key, long-press Enter | X / long-press A | — |
| Play/pause (player) | Space, media keys | A, Start | Click video |
| Fullscreen toggle | F11 | — | — |

Back on Home asks before quitting. Gamepads are read through SDL2 (via JNA), so
they work whether or not the mpv window has focus.

## 6. Player

### 6.1 Session flow

1. Detail page → Play. `shared`'s v3 playback flow starts a session with this
   client's codec capabilities and the user's quality, track, and subtitle
   preferences.
2. Server returns a direct-play URL, or a transcode/remux stream.
3. `player` opens it in mpv; `shared` reports sequenced progress and stop
   events, so resume and watched state stay in sync.
4. On decode failure or a manual quality change, `shared` sends a replan
   request and `player` switches to the new stream at the same position.

### 6.2 Advertised capabilities

Derived at startup from the local mpv/FFmpeg build and GPU (not hardcoded):
video H.264, HEVC (8/10-bit), AV1, VP9, MPEG-2, VC-1; HDR10, HDR10+, HLG,
Dolby Vision 5/8 (7 via HDR10 base layer per `DolbyVisionPolicy`); all common
audio codecs, with passthrough codecs listed only when passthrough is enabled;
text and image subtitles (SRT, ASS/SSA, PGS, VobSub) rendered by mpv.

### 6.3 Video defaults

`vo=gpu-next`, `hwdec=auto-safe` (NVDEC / VA-API / Vulkan), `scale=ewa_lanczossharp`,
`deband=yes`, `target-colorspace-hint=yes` (HDR passthrough when the
compositor supports it), tone-mapping to SDR otherwise. mpv runs as a native
Wayland client; on X11 it uses its own X window.

### 6.4 Overlay

Drawn by Compose in Skyline style and composited into the mpv window via mpv's
`overlay-add` (raw BGRA memory, scaled with `dw`/`dh`). It is redrawn only
while visible or animating.

Contents: title and episode line; progress bar with trickplay thumbnails while
seeking; elapsed/remaining time; play/pause; skip back/forward (configurable);
audio, subtitle, quality/version, and speed menus; subtitle delay; Skip Intro /
Skip Credits from server markers (manual or auto); Next Up card with countdown
at the credits marker or the final 30 seconds; stats panel (codec, resolution,
bitrate, HDR format, hwdec, dropped frames).

**Spike first (implementation step 1):** confirm Compose-rendered overlays
reach ≥ 30 fps of animation through `overlay-add` at 4K output on the reference
machine. **Fallback:** an mpv-side Lua control bar, styled to match, that
receives the same data over mpv script messages.

### 6.5 Error handling

| Failure | Behavior |
|---|---|
| Network drop mid-play | Pause, "Reconnecting…", retry with backoff; resume at the same position when the stream is back |
| Direct play fails to decode | Automatic replan to a transcode; brief notice to the user |
| Session refused by server | Readable message plus Retry / Back |
| HDR not supported by the compositor | Tone-map to SDR silently; stats panel shows "HDR → SDR" |
| mpv/libmpv missing or too old | Startup check with a clear message naming the package to install |

## 7. Error handling (app-wide)

- Server unreachable: cached Home and library pages stay visible, with a banner
  and a retry action; actions that need the server are disabled.
- Token expired or revoked: return to sign-in for that server, keeping the
  server entry.
- Unexpected exceptions: logged with context to the log directory; the user sees
  "Something went wrong" with Retry, never a crash dialog. The player tears down
  mpv cleanly on any fatal error.

## 8. Settings

Settings are stored the way Silo stores them: profile-synced settings use the
server's settings API (via `shared`), device-only settings stay local. Each row
shows whether it is synced or device-only.

| Section | Settings |
|---|---|
| **Playback** | Default quality (Auto / Original / presets); max streaming bitrate; auto-play next episode; Next Up card on/off; skip intros (off / button / auto); skip credits (off / button / auto); skip back and skip forward intervals; resume behavior (ask / always resume / always start over) |
| **Video** | Hardware decoding (auto / off); HDR (passthrough / always tone-map); Dolby Vision on/off and Profile 7 HDR10 fallback; match display refresh rate; deinterlacing (auto / on / off); upscaling quality (balanced / high); debanding |
| **Audio** | Preferred audio language; output (PipeWire default / specific device); channel layout (auto / stereo / 5.1 / 7.1); passthrough toggles (AC-3, E-AC-3, DTS, DTS-HD, TrueHD/Atmos); volume normalization / night mode |
| **Subtitles** | Preferred language; mode (off / forced only / always); show forced subtitles; appearance preset or custom (font family, size, text color, outline color, background style/color/opacity, position); reset |
| **Streaming** | Buffer ahead (seconds); seek cache size |
| **Interface** | Customize top menu; Home sections (show/hide/reorder); card captions; poster size; show advisory age rating; UI scale (90–120%); start in fullscreen; profile at launch (ask / remember last) |
| **Spoilers** | Blur unwatched episode thumbnails; hide unwatched episode descriptions |
| **Controls** | Gamepad layout (Xbox / PlayStation / Nintendo button labels); key bindings view |
| **Account & servers** | Manage servers; switch server; profile; sign out |
| **About** | Version; server version; open-source licenses and Silo attribution; open log folder |

## 9. Packaging and branding

- **Build:** Gradle with the Compose Desktop plugin, producing a JAR plus launcher
  script.
- **AUR `silage`:** builds from a tagged source release. Depends on
  `java-runtime>=21`, `mpv` (libmpv), `sdl2`, `libsecret`. Installs to
  `/usr/share/java/silage/`, `/usr/bin/silage`,
  `/usr/share/applications/silage.desktop`, and hicolor icons.
- **AUR `silage-bin`:** the same files from a prebuilt GitHub release artifact.
- **Desktop entry:** category `AudioVideo;Video;Player;`, `StartupWMClass` set so
  the icon groups correctly.
- **Icon:** redrawn as SVG from `assets/branding/reference-icon.png` (rounded
  square, soft-white teardrop with play cutout). A "kernel" variant (narrower
  body, rounded tip) is produced alongside for a final pick. PNGs exported at
  16, 32, 48, 64, 128, 256, 512.
- **Branding compliance:** no Silo logo, wordmark, or icon files anywhere in the
  repo or package; upstream brand assets are excluded when vendoring. README and
  About say "Silage, a Silo client" and "based on Silo Android", and state that
  Silage is not an official Silo product.

## 10. Testing

- **`shared`:** upstream's `commonTest` suite runs on the `desktop` target; new
  `desktopMain` code (token store, server registry) gets unit tests.
- **`player`:** integration tests against real libmpv with small generated test
  clips (SDR, 10-bit, subtitle tracks): open, seek, track switch, end-of-file,
  error paths.
- **`app`:** Compose UI tests for focus navigation (top bar ↔ rows ↔ pills,
  back behavior) and for settings persistence; view models tested against
  Ktor's mock engine with fixture responses.
- **Manual acceptance on the reference machine:** the success criteria in
  section 1, plus HDR10, Dolby Vision 5/8, TrueHD passthrough, and a gamepad
  run-through.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Overlay through `overlay-add` is not smooth | Spike first (6.4); fallback to a Lua control bar |
| A `commonMain` file depends on Android APIs indirectly | Minimal `expect`/`actual` split, logged in `UPSTREAM.md` |
| Compose Desktop runs under XWayland on Wayland | Acceptable: video does not go through it; only the browsing UI does. Revisit when JetBrains' native Wayland toolkit is stable |
| Upstream `shared` changes break the vendored copy | Pinned commit; updates are deliberate merges with tests |
| Silo server API changes | Same as above; follow silo-android's changes |
