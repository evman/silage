# Silage 0.5.0: Windows installer, Linux repositories and auto-update

Status: draft for review. Target release: **0.5.0**.

## Intent

Silage is a free, couch-first Silo client for Linux and Windows. Today every
update is manual: Windows users download a new portable EXE, Linux users re-run
the install one-liner. People drift onto old versions and bug reports refer to
fixed problems.

0.5.0 makes updating smooth on every platform without ever getting in the way
of watching something:

- Windows installs like Discord: one small installer, no admin prompt, the app
  lives in the user's AppData, with Start menu (so it shows in Windows search)
  and Desktop shortcuts and a normal uninstall entry.
- Linux updates arrive through the system's own package manager, from Silage's
  own signed repositories for Debian/Ubuntu, Fedora and Arch, hosted on GitHub
  with no server.
- Inside Silage, an "Update ready" entry in the sidebar installs the new version
  and restarts.

## Goals

1. One Windows download, `Silage-Setup.exe`, that installs per user into AppData
   with shortcuts, an Apps entry and an uninstaller, and that can update itself.
2. Windows settings, sign-in, caches and logs live in AppData, not beside the EXE.
3. Signed apt, dnf and pacman repositories, generated and published by the
   release workflow, so `apt upgrade`, `dnf upgrade`, `pacman -Syu` and the
   distros' software centers update Silage.
4. An in-app updater: checks in the background, downloads and verifies the new
   version, and shows "Update ready" in the sidebar; selecting it installs and
   restarts.
5. Every update Silage installs is cryptographically verified first.
6. Everything is tested in CI before a release is published, as releases are now.

## Non-goals

- Importing settings or sign-in from the old portable build. **Decided: people
  sign in again and start fresh.**
- macOS, Flatpak/Flathub, Snap.
- winget submission (later; the Inno installer is winget-compatible).
- The AUR (registration is closed; the pacman repository replaces it for updates).
- Delta/partial updates, beta or nightly channels.
- A paid code-signing certificate for the Windows EXE.

## Anti-goals (failure even if it technically works)

- Anything appearing over a playing video, forced restarts, or nagging after the
  user has seen the notice.
- Installing a file whose signature didn't verify.
- Requiring administrator rights on Windows.
- Silage overwriting files a Linux package manager owns, or leaving the package
  manager's records wrong.
- Losing settings or sign-in when updating or reinstalling the same install type.
- Needing a server, hosting bill or paid service.

## Decided by the user

- Smooth auto-updating on all platforms; implement all of it in one release.
- Windows ships as **one installer EXE** that installs into AppData, Discord-style,
  with **Desktop and Start menu shortcuts** (Windows search). The portable EXE
  and the ZIP are dropped.
- Windows data moves to AppData. **No import from portable; users sign in again.**
- Linux: own repositories for Debian/Ubuntu (apt), Fedora (dnf) and Arch (pacman),
  hosted on GitHub, no server.
- "Update ready" is **an entry in the sidebar just above Settings** (mockup
  option A): an amber dot on its icon while the sidebar is collapsed; expanded it
  reads "Update ready" with "Restart to update to X.Y.Z" beneath. Selecting it
  (remote, keyboard or mouse) applies the update.
- On Linux, selecting it shows the system password prompt and installs through
  the system package manager, then restarts. Updates through normal system
  updates also work.
- Nothing during playback; no pop-ups; no forced restarts.

## Design

### 1. Windows installer (Inno Setup)

- Built with Inno Setup in the release workflow's existing `windows` job, from
  the same `createDistributable` app image plus `libmpv-2.dll`, `fallback`,
  licences and the trailer tools that `package-windows.py` assembles today.
- Per-user (`PrivilegesRequired=lowest`), installs to
  `%LOCALAPPDATA%\Programs\Silage`. No wizard pages: it shows progress, then
  launches Silage. (My call: no "create a desktop shortcut" checkbox; the Desktop
  shortcut is always created, matching Discord.)
- Creates a Start menu shortcut (`%APPDATA%\Microsoft\Windows\Start Menu\Programs\Silage.lnk`)
  and a Desktop shortcut, both with the Silage icon and the AppUserModelID
  `dev.silage.Silage`, which Silage also sets on its process so taskbar pinning
  groups correctly.
- Registers in Settings → Apps (name, version, icon, uninstaller).
- Upgrading over an existing install works silently
  (`/VERYSILENT /SUPPRESSMSGBOXES /NORESTART`), closes a running Silage first,
  and replaces the app files without touching data.
- Uninstall removes the app and shortcuts and asks whether to also delete
  settings and sign-in (default: keep). Silent uninstall keeps data.
- Release assets: `Silage-Setup.exe` (stable "latest" link) and
  `Silage-<version>-Setup.exe` (versioned, for winget later). The portable EXE,
  the ZIP, `PortableLauncher.cs` and `package-single-exe.ps1` are removed.
  `SILAGE_PORTABLE_HOME` and the `userdata` logic remain only for development
  runs (`:app:run`), or are removed if nothing needs them (builder's call).
- The EXE stays unsigned (no certificate). SmartScreen may warn on the first
  manual download; in-app updates don't carry the browser's download mark.

### 2. Windows data locations

| Kind | Location |
|---|---|
| Config (settings, `ui.json`) | `%APPDATA%\Silage\Config` |
| Data (session, downloaded trailer tools) | `%APPDATA%\Silage\Data` |
| State | `%LOCALAPPDATA%\Silage\State` |
| Cache, logs, downloaded updates | `%LOCALAPPDATA%\Silage\Cache` |

`AppDirs` keeps its XDG overrides for tests and development. Linux paths are
unchanged.

### 3. Linux repositories

All three are signed with one **repository GPG key** (private key in a GitHub
Actions secret). Packages stay on GitHub Releases; nothing large goes on Pages.

- **apt (Debian, Ubuntu and derivatives):** a flat repository (`Packages`,
  `Release`, `InRelease`, `Release.gpg`) attached to each release, so
  `deb [signed-by=/usr/share/keyrings/silage.gpg] https://github.com/evman/silage/releases/latest/download ./`
  always serves the newest. The `.deb` (`silage_<v>_amd64.deb`) sits beside it.
- **pacman (Arch and derivatives):** `silage.db`/`silage.files` (+ `.sig`) made
  with `repo-add --sign`, attached to the release, with the signed package
  `silage-bin-<v>-1-x86_64.pkg.tar.zst` (+ `.sig`). `pacman.conf`:
  `[silage]` / `Server = https://github.com/evman/silage/releases/latest/download`.
- **dnf (Fedora):** `repodata/` generated with `createrepo_c --baseurl` pointing
  at the release download URL, signed (`repomd.xml.asc`) and published to
  GitHub Pages (`https://evman.github.io/silage/rpm/`); RPMs signed with
  `rpmsign`. A `silage.repo` file is published beside it.
- The release must not become "latest" until every repository file is attached,
  so a user never sees an index pointing at a missing package (create as draft,
  upload, then publish). The Pages update happens after publishing.
- `install.sh` (the README one-liner) on each distro: installs the key, adds the
  repository, installs Silage through it. Re-running it on an existing install
  switches that install to the repository. The tarball path for other distros
  stays as today.

### 4. Update signing (what the app verifies)

- A separate **update signing key**, Ed25519. The release workflow signs
  `SHA256SUMS` (which lists every asset) producing `SHA256SUMS.sig`.
- The public key is compiled into Silage. Before installing anything, Silage
  verifies `SHA256SUMS.sig` with the JDK's built-in Ed25519, then checks the
  downloaded file's SHA-256 against it. Any failure discards the download and
  the update is not offered.
- Private keys (repository GPG and update Ed25519) are generated once, stored as
  GitHub secrets, and backed up by the maintainer outside GitHub. Losing them
  means users must re-add the repository / reinstall once.

### 5. In-app updater

**Install type detection** decides how an update is applied:

| Install | Detected by | Applies updates by |
|---|---|---|
| Windows installer | running from `%LOCALAPPDATA%\Programs\Silage` | downloaded `Silage-<v>-Setup.exe`, run silently |
| Debian/Ubuntu package | `/opt/silage` owned by dpkg | `pkexec apt-get install -y ./silage_<v>_amd64.deb` |
| Fedora package | owned by rpm | `pkexec dnf install -y ./silage-<v>….rpm` |
| Arch package | owned by pacman | `pkexec pacman -U --noconfirm ./silage-bin-<v>….pkg.tar.zst` |
| Tarball | none of the above, writable install dir | replace itself in place, no password |
| Development run | `-dev` version or no launcher | no updates |

**Flow:**

1. Shortly after start (after the splash) and every 6 hours, Silage asks the
   GitHub API for the latest published release
   (`/repos/evman/silage/releases/latest`). Not newer than the running version →
   nothing happens. Network errors are silent and retried at the next interval.
2. Newer and Automatic updates on: download the right asset for this install
   type, plus `SHA256SUMS` and its signature, into the cache folder in the
   background, and verify (section 4).
3. Verified → the sidebar shows the "Update ready" entry (above). It never
   appears during playback (the sidebar isn't shown there) and isn't dismissible;
   it simply stays until applied. A newer release arriving later replaces the
   pending one.
4. Selecting it:
   - Windows: start the installer silently with an argument that relaunches
     Silage when done, then exit.
   - Linux packages: run the package-manager command through `pkexec`. Success →
     relaunch. Cancelled password → keep the entry. No polkit agent or `pkexec`
     → the entry opens a small dialog with the exact command to run.
   - Tarball: swap in the new version, relaunch.
5. Windows only: if the user quits with an update ready, the installer runs
   silently on exit without relaunching.
6. Linux: if the package manager updated Silage underneath (system updates),
   Silage notices the installed version is newer than the running one and the
   same sidebar entry reads "Restart to finish updating", which only relaunches.

**Settings → About** (new section or extended): current version, an
**Automatic updates** switch (on by default, stored in `ui.json`; off = still
check and show the entry, but download only once it's selected), **Check now**,
and a status line (up to date / downloading / ready / last check failed). A
"What's new" link opens the release notes in the browser.

### 6. Release workflow

- Windows job: build the installer instead of the portable EXE/ZIP; test-suite
  run unchanged.
- A signing/repository step (Linux runner) with the secrets: sign packages,
  generate the three repositories, sign `SHA256SUMS`.
- Release created as a draft, all assets attached, then published without
  becoming "latest"; tests run; then it's marked latest and Pages is updated
  (see "Calls made after review", 2).
- Tests before publishing where possible, after publishing as today otherwise:
  - Windows: silent install, `silage --check` and `--version` from the installed
    path, shortcuts and Apps entry exist, silent upgrade over itself, uninstall.
  - Per distro container (Ubuntu 24.04, Debian 13, Fedora 41, Arch): run the
    README one-liner, install through the repository, `silage --check`; and
    verify a package-manager upgrade path works (builder decides how to test an
    upgrade before a second repository release exists, e.g. a locally built
    higher test version in a throwaway repository).
  - Unit tests for version comparison, install-type detection, signature
    verification (including tampered files), and the updater state machine.

### 7. Documentation and release notes

README and WINDOWS.md install sections rewritten (Windows: download
`Silage-Setup.exe`; Linux: the one-liner adds the repository). 0.5.0 notes tell
Windows users to install `Silage-Setup.exe` once and sign in again (their old
portable folder can be deleted), and Linux users to re-run the one-liner once.

## Constraints

- Free and AGPL-3.0-or-later; GitHub only (Releases, Pages, Actions); no server.
- Windows 10 and 11, x64; no admin rights.
- Linux: Ubuntu 24.04+ / Debian 13, currently supported Fedora releases, Arch
  and derivatives.
- Work test-first, as the rest of the codebase; never drive the maintainer's
  desktop during development.

## Calls made after review (not decided by the user)

1. **Keys.** The builder generates both keys on the maintainer's machine with a
   script at the start of the work: the repository GPG key (RSA 4096, accepted
   by apt, current rpm and pacman) and the Ed25519 update key. With the
   maintainer's go-ahead it stores the private halves as repository secrets
   (`gh secret set`, no protected environment, so releases stay one-step), and
   the maintainer backs them up offline. The public halves are committed: the
   Ed25519 key compiled into the app, the GPG public key published as
   `packaging/silage-archive-keyring.gpg`/`.asc` and embedded by `install.sh`.
   CI tests that run on pull requests or before publishing use throwaway test keys.
2. **A bad release never becomes "latest".** Every repository URL and the
   in-app check follow GitHub's "latest" release. The workflow publishes the
   release with `make_latest=false`, runs every install, repository and upgrade
   test against that release's versioned download URLs, and only then marks it
   latest and updates Pages. If a test fails, "latest" stays on the previous
   release, nobody receives the broken one, and the maintainer is alerted by
   the failed run. No automatic deletion.
3. **GitHub Pages.** Not enabled on the repository today and nothing is hosted
   there. The builder enables it with the source set to GitHub Actions (with
   the maintainer's go-ahead) and deploys from `release.yml`. The site holds
   only the dnf repository and the `.repo` file, so a full replace on each
   deploy is fine.
4. **Distros in CI.** Fedora CI moves from 41 to the current Fedora releases
   (`fedora:latest` and the one before it). `install.sh` keeps sending the
   RHEL family (RHEL, CentOS, Alma, Rocky) to the dnf repository as today, but
   it isn't tested in CI and isn't promised. The AUR branch is removed from
   `install.sh` so it can't install a `silage-bin` someone else might register.

## Left to the builder

Inno Setup script details and how it is installed on the runner; exact repository
file names beyond those above; how the tarball swap is done; check interval
jitter; UI copy beyond the strings above; the About layout; how secrets are named;
whether development-only portable code stays.
