# Silage Plan 4: Packaging and Releases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tagging `vX.Y.Z` builds Silage on GitHub Actions and publishes a GitHub Release with an Arch package (via the AUR `silage-bin` PKGBUILD), a `.deb` (Ubuntu 24.04+ / Debian 13), an `.rpm` (Fedora 40+) and a portable `.tar.gz`. Each bundles its own Java runtime and depends on the distro's libmpv. The Ubuntu and Fedora packages are install-tested in containers.

**Architecture:** Compose's `createDistributable` builds a self-contained app image (launcher, jars, trimmed JRE). nfpm turns that image plus the desktop entry, icons and AppStream metainfo into `.deb` and `.rpm`, with per-format mpv dependencies. A PKGBUILD repackages the release tarball for the AUR. The app finds `libmpv.so.2` on every distro. A `--check` flag reports the mpv runtime headlessly, for CI and for users.

**Tech Stack:** Gradle / Compose Desktop `createDistributable`, nfpm 2.x, GitHub Actions, makepkg.

**Spec:** `docs/superpowers/specs/2026-10-11-silage-design.md` §9 Packaging and branding.

## Global Constraints

- Package name `silage`; AUR package `silage-bin` (provides/conflicts `silage`). Install root `/opt/silage`, launcher `/usr/bin/silage`.
- Runtime dependency on libmpv ≥ 0.37 (`keybind`, `input-cursor-passthrough`): Arch `mpv`, Debian/Ubuntu `libmpv2 (>= 0.37)`, Fedora `mpv-libs >= 0.37`.
- Desktop id `dev.silage.Silage`, Categories `AudioVideo;Video;Player;TV;`. Icon is the generated logo (not a Silo mark).
- License AGPL-3.0-or-later. Description: "An unofficial Silo client for Linux".
- The version comes from the tag (`-PappVersion=0.1.0`), with BuildInfo and package metadata agreeing.

## Review Focus

1. **A distro where only `libmpv.so.2` exists** (Ubuntu, Fedora runtime packages): Silage loads it. Tested in Task 1 and by the container install jobs.
2. **mpv missing or older than 0.37:** a clear message naming the package to install, both from `--check` (exit code 2) and as a startup screen. Never a stack trace. Tested in Task 1.
3. **A package built from a tag:** its version, the app's About version and the AUR `pkgver` all match. Checked in Task 3's workflow.
4. **Desktop integration:** the launcher shows in the app menu with the icon; `desktop-file-validate` and `appstreamcli validate` pass in CI.
5. **AUR package correctness:** `namcap` is clean on the PKGBUILD, and `.SRCINFO` matches it.

---

### Task 1: Find libmpv everywhere; report the mpv runtime

**Files:**
- Modify: `player/src/main/kotlin/dev/silage/player/LibMpv.kt` (`load()`)
- Create: `player/src/main/kotlin/dev/silage/player/MpvRuntime.kt`
- Create: `app/src/main/kotlin/dev/silage/app/MpvMissingScreen.kt`; modify `Main.kt` (`--check`, `--version`, startup check)
- Test: `player/src/test/kotlin/dev/silage/player/MpvRuntimeTest.kt`

**Interfaces:**
- Produces:
  - `LibMpv.loadFirst(names: List<String>, loader: (String) -> T): T`, which tries `libmpv.so.2` then `mpv`.
  - `sealed interface MpvStatus { Ok(version); Missing(hint); TooOld(version, hint) }`.
  - `MpvRuntime.check(): MpvStatus`.
  - `MpvRuntime.parseVersion("mpv 0.41.0"): Triple<Int,Int,Int>?`.
  - `MpvRuntime.installHint(osReleaseId: String?, idLike: String?): String` (arch → `sudo pacman -S mpv`, debian/ubuntu → `sudo apt install libmpv2`, fedora → `sudo dnf install mpv-libs`, else a generic line).

- [ ] **Step 1: Write failing tests:**
  - `loadFirst` returns the first loader success and reports every tried name when all fail.
  - `parseVersion` handles `"mpv 0.41.0"`, `"mpv v0.37.0-dirty"` and `"mpv 0.38.0-UNKNOWN"`, and returns null for garbage.
  - `meetsMinimum` accepts 0.37.0 and rejects 0.36.9.
  - `installHint` covers each distro family, including `ID_LIKE` fallback (e.g. Linux Mint → apt).
- [ ] **Step 2: Run them to verify they fail.** `./gradlew :player:test`
- [ ] **Step 3: Implement.** `check()` loads libmpv (missing → `Missing`), creates a handle, reads `mpv-version`, then destroys the handle (too old → `TooOld`).
- [ ] **Step 4:** `silage --check` prints the status and exits 0/2; `silage --version` prints the version. At startup, any status other than Ok shows `MpvMissingScreen` (the logo, the reason, the install command) instead of the app. Tests pass.
- [ ] **Step 5: Commit** `feat: find libmpv.so.2 on every distro; explain a missing or old mpv`

### Task 2: Desktop integration files and versioned build

**Files:**
- Create: `packaging/dev.silage.Silage.desktop`, `packaging/dev.silage.Silage.metainfo.xml`, `packaging/icons/hicolor/{48,64,128,256,512}x…/apps/dev.silage.Silage.png` (rendered by `runLogoRender`)
- Modify: `app/build.gradle.kts` (`appVersion` property → `packageVersion` and a generated BuildInfo), `scripts/` helpers

- [ ] **Step 1:** `BuildInfo.VERSION` comes from Gradle (`-PappVersion`, default `0.1.0-dev`). Test: `BuildInfoTest` checks that the version is non-blank and semver-like.
- [ ] **Step 2:** The desktop entry has `Exec=silage`, `Icon=dev.silage.Silage` and `StartupWMClass` matching the window class. The metainfo gives the summary, description, screenshot URL (`docs/media/home.png` on GitHub), license and release entry.
- [ ] **Step 3:** Validate locally: `desktop-file-validate` and `appstreamcli validate --no-net`, if available. Otherwise CI does it.
- [ ] **Step 4: Commit** `build: desktop entry, AppStream metainfo, icons, versioned builds`

### Task 3: Release workflow (tarball, .deb, .rpm) with install tests

**Files:**
- Create: `packaging/nfpm.yaml`, `scripts/package.sh`, `.github/workflows/release.yml`, `.github/workflows/ci.yml`

- [ ] **Step 1:** `scripts/package.sh VERSION`:
  1. Run `./gradlew :app:createDistributable -PappVersion=VERSION`.
  2. Assemble `dist/silage-VERSION-linux-x86_64.tar.gz` (the app image plus the desktop entry, metainfo and icons, with an `install.sh` for manual installs).
  3. Run `nfpm pkg` for deb and rpm.
- [ ] **Step 2:** `nfpm.yaml` places the app image in `/opt/silage`, symlinks `/usr/bin/silage`, and installs the desktop entry, metainfo and icons. Overrides: deb `depends: [libmpv2 (>= 0.37)]`, rpm `depends: [mpv-libs >= 0.37]`.
- [ ] **Step 3:** `release.yml` runs on `v*` tags:
  - **build:** ubuntu-latest, JDK 21, run `package.sh` with the tag's version, upload the artifacts.
  - **test-deb:** in an `ubuntu:24.04` container, `apt install ./silage*.deb`, then `silage --check` must exit 0.
  - **test-rpm:** in a `fedora:41` container, `dnf install ./silage*.rpm`, then `silage --check` must exit 0.
  - **validate:** desktop-file-validate and appstreamcli.
  - **release:** `gh release create` with the tarball, `.deb`, `.rpm` and SHA-256 sums, once all the tests pass.
- [ ] **Step 4:** `ci.yml` runs on pushes to main: `./gradlew test :shared:desktopTest`.
- [ ] **Step 5: Commit** `ci: release workflow building tarball, .deb and .rpm with install tests`

### Task 4: AUR `silage-bin`

**Files:**
- Create: `packaging/aur/PKGBUILD`, `packaging/aur/.SRCINFO`

- [ ] **Step 1:** The PKGBUILD:
  - Source is the release tarball. `sha256sums` comes from the release's `SHA256SUMS`.
  - `depends=(mpv hicolor-icon-theme)`.
  - `package()` installs `/opt/silage`, `/usr/bin/silage`, the desktop entry, metainfo, icons and the license.
- [ ] **Step 2:** After the release exists, run `makepkg -f` locally (build only, not installed), `namcap` on the PKGBUILD and on the built package, then `makepkg --printsrcinfo > .SRCINFO`.
- [ ] **Step 3: Commit** `packaging: AUR silage-bin PKGBUILD`
- [ ] **Step 4:** Publish to the AUR. This needs the user's AUR account and SSH key: `git push` to `ssh://aur@aur.archlinux.org/silage-bin.git`. Confirm with the user first.

### Task 5: README install instructions; cut v0.1.0

- [ ] **Step 1:** Add README install sections:
  - **Arch:** `yay -S silage-bin`.
  - **Ubuntu/Debian:** `sudo apt install ./silage_*.deb`.
  - **Fedora:** `sudo dnf install ./silage-*.rpm`, plus the RPM Fusion FFmpeg note.
  - **Anywhere:** the tarball with `install.sh`.
- [ ] **Step 2:** Tag `v0.1.0`, push the tag, watch the workflow, and check the release assets and the install-test jobs.

## Out of scope

Fedora COPR, an Ubuntu apt repo or PPA, Flathub, and a build-from-source AUR `silage` package can follow once releases are flowing.
