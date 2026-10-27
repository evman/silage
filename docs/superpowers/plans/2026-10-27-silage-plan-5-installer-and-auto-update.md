# Silage Plan 5: Windows Installer, Linux Repositories and Auto-Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Silage 0.5.0 installs on Windows with one per-user `Silage-Setup.exe`, updates on Linux from its own signed apt/dnf/pacman repositories, and updates itself from an "Update ready" sidebar entry, with every update signature-checked.

**Architecture:** A pure, unit-tested `dev.silage.app.update` package (version compare, release lookup, install-type detection, signature check, state machine) drives a thin platform layer (silent Inno installer on Windows, `pkexec` + package manager on Linux, self-swap for the tarball). The release workflow signs `SHA256SUMS` with Ed25519, builds the three repositories with one GPG key, publishes the release without making it "latest", tests installs and upgrades against it, then marks it latest and deploys the dnf index to Pages.

**Tech Stack:** Kotlin 2.1 / Compose Desktop 1.7.3, JDK 21 (`java.net.http`, `java.security` Ed25519), JNA, Inno Setup 6, nfpm, `apt-ftparchive`, `repo-add`, `createrepo_c`, `rpmsign`, GnuPG, OpenSSL 3, GitHub Actions/Pages.

**Spec:** `docs/superpowers/specs/2026-10-27-installer-and-auto-update-design.md`

## Global Constraints

- Version **0.5.0**. Free, AGPL-3.0-or-later. GitHub only (Releases, Pages, Actions); no server, no paid service.
- Windows 10/11 x64, **no admin rights**: install to `%LOCALAPPDATA%\Programs\Silage`; data in `%APPDATA%\Silage\{Config,Data}`, `%LOCALAPPDATA%\Silage\{State,Cache}`.
- **No import from the portable build**; people sign in again.
- Windows release assets: `Silage-Setup.exe` and `Silage-<v>-Setup.exe` only (portable EXE and ZIP removed).
- AppUserModelID `dev.silage.Silage`.
- Never install a file whose Ed25519-signed `SHA256SUMS` entry doesn't match.
- Nothing during playback; no pop-ups; no forced restarts; the sidebar entry is not dismissible.
- Linux packages keep their names: `silage` (deb/rpm), `silage-bin` (pacman). Repository name `silage`.
- Repository URLs: apt and pacman `https://github.com/evman/silage/releases/latest/download`; dnf `https://evman.github.io/silage/rpm/`.
- Only `evman` and `minimalhandle` may create, move or delete `v*` tags.
- Test-first; never launch or drive the GUI on the maintainer's desktop (off-screen renders and tests only).
- Commit messages: plain sentence-case subject, no attribution lines.

## Review Focus

1. **The release's "latest" asset set changes mid-download** (a user's check races a new publish): the app must verify against the `SHA256SUMS` from the same release tag it downloaded from, never `latest/download`. Pinned in Task 6 (`downloadsComeFromTheTaggedRelease`).
2. **The installed Linux package was upgraded underneath a running Silage** (system updates): the entry must read "Restart to finish updating" and only relaunch, never reinstall. Pinned in Task 6 (`systemUpdatedUnderneathOnlyOffersRestart`).
3. **A cancelled password prompt / missing polkit agent**: the entry stays, and with no `pkexec` the user gets the exact command instead of an error. Pinned in Task 7 (`cancelledPasswordKeepsTheUpdate`, `withoutPkexecShowsTheCommand`).
4. **GitHub unreachable or rate-limited (HTTP 403/429) at every check**: silent, no entry, retry next interval, no crash. Pinned in Task 6 (`rateLimitedCheckIsSilent`).
5. **A development or unknown install** (`-dev` version, `:app:run`, read-only tarball in `/usr/local`): never offers to install; a read-only tarball shows the entry as "Update available" linking to the release page. Pinned in Task 5 (`devRunsNeverUpdate`, `readOnlyTarballCanOnlyNotify`).

---

### Task 1: Keys, release-tag rule and Pages (maintainer setup, scripted)

**Files:**
- Create: `scripts/release-keys.sh` (generates keys into a directory outside the repo; prints next steps)
- Create: `packaging/keys/silage-archive-keyring.asc`, `packaging/keys/silage-archive-keyring.gpg` (GPG public key, armored and binary)
- Create: `app/src/main/resources/update-key.pub` (Ed25519 public key, X.509 SubjectPublicKeyInfo, PEM)
- Create: `docs/RELEASING.md` (who can release, secrets, key backup and rotation)

**Interfaces:**
- Produces secrets (names fixed for later tasks): `REPO_GPG_PRIVATE_KEY` (armored), `REPO_GPG_KEY_ID` (long fingerprint), `UPDATE_SIGNING_KEY` (Ed25519 private key, PKCS#8 PEM).
- Produces classpath resource `/update-key.pub`.

- [ ] **Step 1:** Write `scripts/release-keys.sh OUT_DIR`: `gpg --batch --quick-gen-key "Silage Releases <evman311@gmail.com>" rsa4096 sign never` in a temporary `GNUPGHOME`; export public (`.asc`, `.gpg`) and private armored; `openssl genpkey -algorithm ed25519 -out update.key` and `openssl pkey -in update.key -pubout -out update-key.pub`. Refuses to overwrite existing files. `shellcheck` clean.
- [ ] **Step 2:** Run it into `~/silage-release-keys` (outside the repo, mode 700). Expected: five files; `gpg --show-keys packaging/...asc` lists one RSA 4096 signing key.
- [ ] **Step 3:** Copy the public halves into the repo paths above. With the maintainer's go-ahead (already given), set the three secrets with `gh secret set --repo evman/silage`.
- [ ] **Step 4:** Create the tag ruleset: `gh api repos/evman/silage/rulesets` POST, target `tag`, include `refs/tags/v*`, rules `creation`, `update`, `deletion`, bypass actors: repository admin role plus a bypass for `minimalhandle` (if a per-user bypass isn't available on this plan, restrict via "Restrict creations" with the write role excluded and document the limitation in `docs/RELEASING.md`). Expected: `gh api repos/evman/silage/rulesets` lists it.
- [ ] **Step 5:** Enable Pages with source GitHub Actions: `gh api -X POST repos/evman/silage/pages -f build_type=workflow`. Expected: `gh api repos/evman/silage/pages` returns `build_type: workflow`.
- [ ] **Step 6:** Write `docs/RELEASING.md` and tell the maintainer to back up `~/silage-release-keys` offline (and share it with the co-maintainer if desired), then delete the local copy. Commit `scripts/release-keys.sh`, public keys, `update-key.pub`, `docs/RELEASING.md`: "Add release signing keys and the release guide".

### Task 2: Windows data in AppData

**Files:**
- Modify: `app/src/main/kotlin/dev/silage/app/XdgPaths.kt` (`AppDirs.resolve`)
- Test: `app/src/test/kotlin/dev/silage/app/AppDirsTest.kt`

**Interfaces:**
- Produces: `AppDirs(osName, env, home, launcherPath, workingDirectory)` unchanged signature; on Windows with a launcher, `resolve(CONFIG) = %APPDATA%\Silage\Config`, `DATA = %APPDATA%\Silage\Data`, `STATE = %LOCALAPPDATA%\Silage\State`, `CACHE = %LOCALAPPDATA%\Silage\Cache` (from `env("APPDATA")` / `env("LOCALAPPDATA")`, falling back to `home\AppData\Roaming` / `home\AppData\Local`).

- [ ] **Step 1:** Replace the portable tests (`windowsKeepsAllDataBesideTheExecutable…`, `movingTheApp…`, `singleFileLauncher…`) with: `windowsInstalledAppKeepsSettingsInRoamingAppData` (CONFIG/DATA under `APPDATA`), `windowsCachesAndStateStayLocal` (STATE/CACHE under `LOCALAPPDATA`), `windowsFallsBackToTheProfileWhenAppDataIsUnset`, `windowsNeverReadsTheOldPortableUserdata` (a `userdata` dir beside the launcher is ignored). Keep `windowsSourceRunUsesTheDevelopmentDirectory`, `explicitXdgOverridesStillAllowIsolatedWindowsRuns` and the Linux tests unchanged.
- [ ] **Step 2:** `./gradlew :app:test --tests 'dev.silage.app.AppDirsTest'`. Expected: the four new tests FAIL.
- [ ] **Step 3:** Implement; `SILAGE_PORTABLE_HOME` and the source-run `userdata` stay only for runs without a launcher (development). Update the class KDoc.
- [ ] **Step 4:** Same command. Expected: PASS. Then `./gradlew :app:test`. Expected: PASS.
- [ ] **Step 5:** Commit "Keep Windows settings and caches in AppData".

### Task 3: Inno Setup installer replaces the portable builds

**Files:**
- Create: `packaging/windows/silage.iss`
- Modify: `package-windows.py` (build `dist\Silage` as today, then `ISCC` → `dist\Silage-<v>-Setup.exe` + copy `dist\Silage-Setup.exe`; drop ZIP and portable EXE)
- Delete: `packaging/windows/PortableLauncher.cs`, `scripts/package-single-exe.ps1`
- Modify: `scripts/prepare-windows.ps1` (ensure Inno Setup 6: use `ISCC.exe` if present, else `choco install innosetup -y --no-progress`)
- Modify: `app/src/main/kotlin/dev/silage/app/WindowsChrome.kt` (set AppUserModelID at startup via `shell32.SetCurrentProcessExplicitAppUserModelID("dev.silage.Silage")`, called from `Main.kt` before the first window, Windows only)
- Modify: `.github/workflows/release.yml` (`windows` job uploads the two setup EXEs; `install-windows` job rewritten)
- Test: new `app/src/test/kotlin/dev/silage/app/AppUserModelIdTest.kt` (the call is skipped off Windows)

**Interfaces:**
- Produces: installer CLI used by Task 7: `Silage-<v>-Setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /RELAUNCH` (custom `/RELAUNCH` param: start Silage after install). Without `/RELAUNCH` a silent install doesn't start Silage; an interactive install always does.
- Produces: installed layout `%LOCALAPPDATA%\Programs\Silage\silage.exe`.

`silage.iss` pins: `AppId={{<new GUID, fixed forever>}`, `AppName=Silage`, `AppVersion={#Version}`, `PrivilegesRequired=lowest`, `DefaultDirName={localappdata}\Programs\Silage`, `DisableWelcomePage=yes`, `DisableDirPage=yes`, `DisableProgramGroupPage=yes`, `DisableReadyPage=yes`, `DisableFinishedPage=yes`, `CloseApplications=force`, `RestartApplications=no`, `UninstallDisplayIcon={app}\silage.exe`, `SetupIconFile=silage.ico`, `OutputBaseFilename=Silage-{#Version}-Setup`, `WizardStyle=modern`. `[Icons]`: `{userprograms}\Silage` and `{userdesktop}\Silage` with `AppUserModelID: "dev.silage.Silage"`. `[Run]`: launch `{app}\silage.exe` with `Flags: nowait postinstall` when not silent or when `/RELAUNCH` is present. `[Code]`: on interactive uninstall ask "Also delete your Silage settings and sign-in?" (default No) and delete `{userappdata}\Silage` and `{localappdata}\Silage` only on Yes.

- [ ] **Step 1:** Failing test for the AUMID helper: `appUserModelIdIsSetOnlyOnWindows` (a fake shell32 records calls; Linux → no call; Windows → `"dev.silage.Silage"`). Run `./gradlew :app:test --tests '*AppUserModelId*'`. Expected: FAIL.
- [ ] **Step 2:** Implement the helper and call it from `Main.kt`. Same command. Expected: PASS.
- [ ] **Step 3:** Write `silage.iss` and the `package-windows.py` / `prepare-windows.ps1` changes; delete the portable files; remove portable references from `release.yml`'s `windows` job and from the `release` job's asset globs (`*.zip`, `Silage-Portable.exe`).
- [ ] **Step 4:** Rewrite `install-windows` (runs on the not-yet-latest release, see Task 4): download `Silage-<v>-Setup.exe` + `SHA256SUMS`, verify hash; run `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART`; assert `%LOCALAPPDATA%\Programs\Silage\silage.exe --check` exits 0 and `--version` prints `Silage <v>`; assert both `.lnk` files exist and the uninstall key `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\{AppId}_is1` exists; run the installer again silently (upgrade over itself) and re-check; run `unins000.exe /VERYSILENT` and assert the app dir and shortcuts are gone and `%APPDATA%\Silage` (pre-created with a marker file) still exists.
- [ ] **Step 5:** `./gradlew :app:test`. Expected: PASS. (The Windows job itself is verified by the CI rehearsal in Task 9.)
- [ ] **Step 6:** Commit "Install Silage on Windows with a per-user setup".

### Task 4: Signed checksums, repositories and a safe publish order

**Files:**
- Create: `scripts/sign-release.sh DIST_DIR` (writes `SHA256SUMS` and `SHA256SUMS.sig`)
- Create: `scripts/build-repos.sh DIST_DIR VERSION OUT_PAGES_DIR` (apt, pacman, dnf)
- Create: `scripts/tests/repo-install-test.sh IMAGE BASE_URL PAGES_URL` (used in CI containers)
- Modify: `packaging/nfpm.yaml` (rpm signing via `rpm.signature.key_file`), `.github/workflows/release.yml`, `.github/workflows/install-test.yml`
- Test: `scripts/tests/test-sign-release.sh`, `scripts/tests/test-build-repos.sh` (run in CI `build` job with throwaway keys)

**Interfaces:**
- Consumes: secrets from Task 1.
- Produces release assets: `SHA256SUMS`, `SHA256SUMS.sig` (raw 64-byte Ed25519 signature over the exact `SHA256SUMS` bytes, base64 on a single line); apt `Packages`, `Packages.gz`, `Release`, `InRelease`, `Release.gpg`; pacman `silage.db`, `silage.db.sig`, `silage.files`, `silage.files.sig`, `silage-bin-<v>-1-x86_64.pkg.tar.zst.sig`; dnf Pages tree `rpm/repodata/*`, `rpm/repodata/repomd.xml.asc`, `rpm/silage.repo`, `rpm/silage.asc`.
- Note: release asset names can't contain `/`; apt `Filename:` entries are `./silage_<v>_amd64.deb`. pacman's `silage.db` must be a real file (not a symlink) as a release asset.

- [ ] **Step 1:** Write `test-sign-release.sh`: with a throwaway key, signing a temp dir produces a signature `openssl pkeyutl -verify -pubin -rawin` accepts; changing one byte of `SHA256SUMS` makes verification fail. Run it. Expected: FAIL (script missing).
- [ ] **Step 2:** Implement `sign-release.sh` (`openssl pkeyutl -sign -rawin -inkey`, key from `UPDATE_SIGNING_KEY` env written to a 0600 temp file). Run the test. Expected: PASS.
- [ ] **Step 3:** Write `test-build-repos.sh`: with a throwaway GPG key and dummy packages built by nfpm/makepkg in the test, `build-repos.sh` output passes `gpg --verify InRelease`, `repo-add`'s db lists `silage-bin`, `repomd.xml.asc` verifies, and `rpm -K` on the rpm reports a valid signature. Run it (in the arch/fedora containers as needed). Expected: FAIL.
- [ ] **Step 4:** Implement `build-repos.sh` (`apt-ftparchive packages . > Packages`, `apt-ftparchive release` + `gpg --clearsign` / `-abs`; `repo-add --sign --key "$REPO_GPG_KEY_ID" silage.db.tar.gz` then copy `.db.tar.gz`→`silage.db` and `.files.tar.gz`→`silage.files` as real files with signatures; `gpg --detach-sign` the pkg; `createrepo_c --baseurl https://github.com/evman/silage/releases/download/v<v>/ rpm/` and `gpg --detach-sign --armor repomd.xml`; write `silage.repo` with `repo_gpgcheck=1`, `gpgcheck=1`, `gpgkey=https://evman.github.io/silage/rpm/silage.asc`). Run the test. Expected: PASS.
- [ ] **Step 5:** Restructure `release.yml`:
  1. `release` job: sign the rpm (nfpm with key file), run `build-repos.sh` and `sign-release.sh`, `gh release create … --draft`, upload all assets, then `gh release edit --draft=false --latest=false`.
  2. `install-test` matrix (`ubuntu:24.04`, `debian:trixie`, `fedora:latest`, the previous Fedora release pinned by number, `archlinux:latest`) runs `repo-install-test.sh` against `https://github.com/evman/silage/releases/download/$GITHUB_REF_NAME` (apt/pacman) and a locally served copy of the Pages tree (dnf, `python3 -m http.server`), plus the README one-liner pointed at the same tagged URLs (Task 5 adds `SILAGE_REPO_BASE`).
  3. Upgrade test per distro: install the previous release's package directly, then point at the new repository and run the package manager's upgrade; assert `silage --version` = new version. (For 0.5.0, "previous" is the 0.4.1 package.)
  4. `promote` job (needs all tests incl. `install-windows`): `gh release edit "$GITHUB_REF_NAME" --latest`, then deploy the Pages tree (`actions/upload-pages-artifact` + `actions/deploy-pages`, permissions `pages: write`, `id-token: write`). Tags containing `-` are prereleases and skip `promote`.
  5. Keep the version-free copies (`silage_amd64.deb`, `silage.x86_64.rpm`, `silage-linux-x86_64.tar.gz`, `silage-bin-x86_64.pkg.tar.zst`) for existing links.
  6. Fedora CI image `fedora:41` → `fedora:latest` and the previous release, in `release.yml` and `install-test.yml`.
- [ ] **Step 6:** `actionlint .github/workflows/*.yml` (binary downloaded into the scratchpad). Expected: no errors.
- [ ] **Step 7:** Commit "Sign releases and publish apt, dnf and pacman repositories".

### Task 5: Install script adds the repository; install-type detection in the app

**Files:**
- Modify: `install.sh` (and `packaging/install.sh` if it is the tarball's copy; keep them consistent)
- Create: `app/src/main/kotlin/dev/silage/app/update/InstallType.kt`
- Test: `app/src/test/kotlin/dev/silage/app/update/InstallTypeTest.kt`; `scripts/tests/repo-install-test.sh` (Task 4) exercises `install.sh`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface InstallType {
      data class WindowsInstaller(val installDir: Path) : InstallType
      data object Deb : InstallType
      data object Rpm : InstallType
      data object Pacman : InstallType
      data class Tarball(val installDir: Path, val writable: Boolean) : InstallType
      data object Development : InstallType
  }
  enum class PackageOwner { Dpkg, Rpm, Pacman }
  fun detectInstallType(osName: String, version: String, launcherPath: String?, env: (String) -> String?, owner: (Path) -> PackageOwner?, isWritable: (Path) -> Boolean): InstallType
  fun systemPackageOwner(path: Path): PackageOwner?   // dpkg -S / rpm -qf / pacman -Qo, first that succeeds, 5 s timeout each
  ```
- `install.sh` honours `SILAGE_REPO_BASE` (default `https://github.com/evman/silage/releases/latest/download`) and `SILAGE_RPM_REPO` (default `https://evman.github.io/silage/rpm`) for CI.

- [ ] **Step 1:** `InstallTypeTest`: `windowsInstalledAppIsTheInstaller` (launcher under `LOCALAPPDATA\Programs\Silage`), `windowsElsewhereIsDevelopment`, `debRpmAndPacmanOwnersMapToTheirTypes` (launcher `/opt/silage/bin/silage`), `unownedLinuxInstallIsATarball`, `devRunsNeverUpdate` (`0.5.0-dev` or null launcher → `Development` on both OSes), `readOnlyTarballCanOnlyNotify` (`Tarball(writable=false)`). Run `./gradlew :app:test --tests '*InstallTypeTest'`. Expected: FAIL.
- [ ] **Step 2:** Implement. Same command. Expected: PASS.
- [ ] **Step 3:** `install.sh`: Debian/Ubuntu → write `/usr/share/keyrings/silage-archive-keyring.gpg` (embedded base64 of `packaging/keys/silage-archive-keyring.gpg`) and `/etc/apt/sources.list.d/silage.list`, `apt-get update`, `apt-get install -y silage`. Fedora/RHEL family → `rpm --import` the embedded armored key, write `/etc/yum.repos.d/silage.repo`, `dnf install -y silage`. Arch → `pacman-key --add` + `--lsign-key <fingerprint>`, append the `[silage]` section to `/etc/pacman.conf` only if absent, then `pacman -Syu --noconfirm silage-bin` after asking (a full upgrade, as Arch requires; never `-Sy` alone). Remove the AUR branch. Re-running on an existing install converts it (all steps idempotent). Keep the tarball path and the existing DV-helper migration for Arch.
- [ ] **Step 4:** Run `scripts/tests/repo-install-test.sh` locally in podman/docker for `ubuntu:24.04`, `fedora:latest`, `archlinux:latest` against a local repo built with throwaway keys (`test-build-repos.sh` output served by `python3 -m http.server`). Expected: each installs and `silage --check` exits 0.
- [ ] **Step 5:** `shellcheck install.sh scripts/*.sh scripts/tests/*.sh`. Expected: clean. Commit "Install from the Silage repositories and detect how Silage was installed".

### Task 6: Update core: versions, release lookup, verification, state

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/update/Version.kt`, `ReleaseSource.kt`, `UpdateVerifier.kt`, `Updater.kt`
- Modify: `app/build.gradle.kts` (write `version.txt` into the app image's `app` resources dir)
- Test: `app/src/test/kotlin/dev/silage/app/update/{VersionTest,ReleaseSourceTest,UpdateVerifierTest,UpdaterTest}.kt`

**Interfaces:**
- Consumes: `InstallType` (Task 5), resource `/update-key.pub` (Task 1).
- Produces:
  ```kotlin
  data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> { companion object { fun parse(s: String): Version? } } // "v0.5.1" or "0.5.1"; null for "0.5.0-dev" and garbage
  data class Release(val tag: String, val version: Version, val notesUrl: String, val assets: Map<String, String>) // asset name -> browser_download_url
  interface ReleaseSource { suspend fun latest(): Release? }   // GitHubReleaseSource(http = HttpClient.newHttpClient(), repo = "evman/silage"); null on any error incl. 403/429
  fun assetFor(type: InstallType, version: Version): String?   // WindowsInstaller "Silage-<v>-Setup.exe", Deb "silage_<v>_amd64.deb", Rpm "silage-<v>-1.x86_64.rpm", Pacman "silage-bin-<v>-1-x86_64.pkg.tar.zst", Tarball "silage-<v>-linux-x86_64.tar.gz", Development null
  class UpdateVerifier(publicKeyPem: String) { fun verify(sums: ByteArray, signatureBase64: String, fileName: String, file: Path): Boolean }
  sealed interface UpdateState {
      data object Idle : UpdateState; data object Checking : UpdateState
      data class Downloading(val version: Version, val fraction: Float) : UpdateState
      data class Ready(val version: Version, val file: Path?) : UpdateState        // file null = auto-download off, fetched on apply
      data class RestartToFinish(val version: Version) : UpdateState
      data class Available(val version: Version, val pageUrl: String) : UpdateState // read-only tarball
      data class Failed(val reason: String) : UpdateState
  }
  class Updater(running: Version?, type: InstallType, source: ReleaseSource,
                download: suspend (url: String, to: Path, progress: (Float) -> Unit) -> Unit,
                verifier: UpdateVerifier, cacheDir: Path, installedVersion: () -> Version?,
                autoDownload: () -> Boolean, scope: CoroutineScope, intervalMs: Long = 6 * 3_600_000L) {
      val state: StateFlow<UpdateState>
      fun start()            // first check after 20 s, then every intervalMs
      fun checkNow()
      suspend fun fetchPending(): Path?   // used by apply when Ready.file == null
  }
  ```
- `installedVersion()` reads `version.txt` from the installed app image (Linux `/opt/silage/lib/app/version.txt`; Windows `<installDir>\app\version.txt`).

- [ ] **Step 1:** Tests:
  - `VersionTest`: `parsesTagsWithOrWithoutV`, `devAndGarbageAreNull`, `ordersNumerically` (0.10.0 > 0.9.9).
  - `ReleaseSourceTest` (local `com.sun.net.httpserver.HttpServer`): `readsTagAndAssets`, `rateLimitedCheckIsSilent` (403 and 429 → null), `malformedJsonIsNull`.
  - `UpdateVerifierTest` (throwaway key from `KeyPairGenerator.getInstance("Ed25519")`): `acceptsAGoodSignatureAndHash`, `rejectsATamperedFile`, `rejectsATamperedSumsFile`, `rejectsAFileMissingFromSums`, `rejectsABadSignature`.
  - `UpdaterTest` (fake source/downloader, `TestScope`): `notNewerStaysIdle`, `newerDownloadsVerifiesAndIsReady`, `failedVerificationDeletesTheFileAndIsNotReady`, `downloadsComeFromTheTaggedRelease` (requested URLs are `…/releases/download/<tag>/…`, never `latest`), `autoDownloadOffIsReadyWithoutAFile`, `systemUpdatedUnderneathOnlyOffersRestart` (`installedVersion()` > running → `RestartToFinish`), `readOnlyTarballIsOnlyAvailable`, `developmentNeverChecks`, `aNewerReleaseReplacesAPendingOne`, `checksRepeatEveryInterval`.
- [ ] **Step 2:** `./gradlew :app:test --tests 'dev.silage.app.update.*'`. Expected: FAIL (types missing).
- [ ] **Step 3:** Implement (GitHub API `GET https://api.github.com/repos/evman/silage/releases/latest`, headers `Accept: application/vnd.github+json`, `User-Agent: Silage/<version>`; kotlinx.serialization; downloads stream to `<cache>/updates/<name>.part` then rename); add `version.txt` generation.
- [ ] **Step 4:** Same command. Expected: PASS. `./gradlew :app:test`. Expected: PASS.
- [ ] **Step 5:** Commit "Check for, download and verify Silage updates".

### Task 7: Applying an update on each platform

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/update/UpdateApplier.kt`
- Modify: `app/src/main/kotlin/dev/silage/app/Main.kt` (`closeSilage` calls `UpdateApplier.onExit`)
- Test: `app/src/test/kotlin/dev/silage/app/update/UpdateApplierTest.kt`

**Interfaces:**
- Consumes: `InstallType`, `UpdateState` (Task 6), installer CLI (Task 3).
- Produces:
  ```kotlin
  sealed interface ApplyResult { data object Restarting : ApplyResult; data object Cancelled : ApplyResult; data class NeedsCommand(val command: String) : ApplyResult; data class Failed(val message: String) : ApplyResult }
  class UpdateApplier(type: InstallType, run: (List<String>) -> Int, startDetached: (List<String>) -> Unit,
                      which: (String) -> Path?, relaunch: () -> Unit, exit: () -> Unit) {
      fun commandFor(file: Path): List<String>
      fun apply(file: Path): ApplyResult
      fun restartOnly(): ApplyResult
      fun onExit(state: UpdateState)    // Windows: silent install on quit when Ready(file != null), no relaunch
  }
  ```
  | Type | `commandFor` |
  |---|---|
  | WindowsInstaller | `[file, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/RELAUNCH"]` (started detached, then `exit()`) |
  | Deb | `["pkexec", "apt-get", "install", "-y", file]` |
  | Rpm | `["pkexec", "dnf", "install", "-y", file]` |
  | Pacman | `["pkexec", "pacman", "-U", "--noconfirm", file]` |
  | Tarball(writable) | no command: extract beside the install dir, swap by rename (old kept as `<dir>.old` until next start), relaunch |
- `pkexec` exit 126/127 → `Cancelled`; `which("pkexec") == null` → `NeedsCommand("sudo apt-get install -y <file>")` (dnf/pacman equivalents).

- [ ] **Step 1:** Tests: `windowsStartsTheSilentInstallerAndExits`, `windowsQuitWithAReadyUpdateInstallsWithoutRelaunch`, `debRpmAndPacmanUsePkexecWithTheirPackageManager`, `cancelledPasswordKeepsTheUpdate` (126 → `Cancelled`, no relaunch), `withoutPkexecShowsTheCommand`, `failedInstallReportsAndDoesNotRelaunch` (exit 1), `successRelaunches`, `writableTarballSwapsAndRelaunches` (temp dirs), `restartToFinishOnlyRelaunches`. Run `./gradlew :app:test --tests '*UpdateApplierTest'`. Expected: FAIL.
- [ ] **Step 2:** Implement; relaunch = start the launcher (`jpackage.app-path`) detached, then `exitApplication()`. Wire `onExit` into `closeSilage` in `Main.kt`.
- [ ] **Step 3:** Same command. Expected: PASS. `./gradlew :app:test`. Expected: PASS.
- [ ] **Step 4:** Commit "Install updates and restart on each platform".

### Task 8: Sidebar entry and Settings → About

**Files:**
- Modify: `app/src/main/kotlin/dev/silage/app/shell/Sidebar.kt` (new param `update: SidebarUpdate? = null`; entry between the scrolling list and Settings)
- Modify: `app/src/main/kotlin/dev/silage/app/shell/AppShell.kt` (collect `Updater.state`, map to `SidebarUpdate`)
- Modify: `app/src/main/kotlin/dev/silage/app/settings/SettingsScreen.kt` (`AboutSection`)
- Modify: `app/src/main/kotlin/dev/silage/app/ui/UiPrefs.kt` (`automaticUpdates: Boolean = true`)
- Modify: `app/src/main/kotlin/dev/silage/app/di/DesktopModule.kt` (Koin singletons `ReleaseSource`, `Updater`, `UpdateApplier`; updater started after the splash)
- Test: `app/src/test/kotlin/dev/silage/app/shell/SidebarUpdateTest.kt`, `app/src/test/kotlin/dev/silage/app/settings/AboutUpdatesTest.kt`, `UiPrefsTest`

**Interfaces:**
- Consumes: `Updater`, `UpdateApplier` (Tasks 6–7).
- Produces: `data class SidebarUpdate(val title: String, val subtitle: String, val onSelect: () -> Unit)` and `fun sidebarUpdateFor(state: UpdateState): Pair<String, String>?`:
  | State | title | subtitle |
  |---|---|---|
  | `Ready` | `Update ready` | `Restart to update to <v>` |
  | `RestartToFinish` | `Update ready` | `Restart to finish updating to <v>` |
  | `Available` | `Update available` | `Silage <v>` (selecting opens the release page) |
  | others | none | |
- Visuals (approved mockup option A): `Silage.Amber` text on `Silage.Amber.copy(alpha = 0.12f)`; icon `Icons.Outlined.SystemUpdateAlt` with a 9.dp amber dot when collapsed; subtitle `Silage.Text2` 14.sp under the label when open; focusable and selectable like other entries. `ApplyResult.NeedsCommand` opens a small dialog showing the command; `Failed` shows its message in that dialog.
- About additions: "Automatic updates" switch (`UiPrefs.automaticUpdates`), "Check now" → `Updater.checkNow()`, status text (`Up to date`, `Downloading 0.5.1… 42%`, `Silage 0.5.1 is ready`, `Couldn't check for updates`), "What's new" link opening `Release.notesUrl`.

- [ ] **Step 1:** Tests: `sidebarUpdateFor` table rows; Compose UI `updateEntrySitsAboveSettingsAndSelectsWithEnter`, `noEntryWithoutAnUpdate`, `collapsedEntryShowsOnlyTheIconWithADot` (content description `Update ready`); `AboutUpdatesTest`: `switchPersists`, `checkNowCallsTheUpdater`, `statusTextFollowsState`; `UiPrefsTest`: `automaticUpdatesDefaultsOnAndRoundTrips` (old file without the field → true). Run `./gradlew :app:test --tests '*SidebarUpdateTest' --tests '*AboutUpdatesTest' --tests '*UiPrefsTest'`. Expected: FAIL.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Same command. Expected: PASS. `./gradlew :app:test`. Expected: PASS.
- [ ] **Step 4:** Off-screen render (temporary `ImageComposeScene` test, deleted after) of the sidebar collapsed and open in `Ready` state at 1920×1080. Expected: entry above Settings, amber, matching the mockup and copy.
- [ ] **Step 5:** Commit "Show Update ready in the sidebar and update settings in About".

### Task 9: Docs, 0.5.0 release prep and a full CI rehearsal

**Files:**
- Modify: `README.md`, `WINDOWS.md`, `CHANGELOG.md`, `packaging/release-notes.md`, `packaging/dev.silage.Silage.metainfo.xml`, `app/build.gradle.kts` (`0.5.0-dev`), `packaging/aur/PKGBUILD` (pkgver in sync for the pacman package build)

- [ ] **Step 1:** README: Windows → `releases/latest/download/Silage-Setup.exe`; Linux → the one-liner adds the repository, plus manual apt/dnf/pacman repository lines. WINDOWS.md: installer, data locations, uninstall; portable sections removed. Release notes: Windows users install `Silage-Setup.exe` once and sign in again (old portable folder can be deleted); Linux users re-run the one-liner once.
- [ ] **Step 2:** CHANGELOG `## 0.5.0 — <date>` and metainfo `<release version="0.5.0">` in the 0.4.x style; `appstreamcli validate --no-net packaging/dev.silage.Silage.metainfo.xml`. Expected: valid.
- [ ] **Step 3:** `./gradlew test :shared:desktopTest`. Expected: 0 failures.
- [ ] **Step 4:** Rehearse the release workflow without touching "latest": push `main`, tag `v0.5.0-rc.1` (prerelease path from Task 4 step 5.4, no promote/Pages). Watch the run. Expected: every job green, including `install-windows` and each repository install and upgrade test. Then delete the rc release and tag.
- [ ] **Step 5:** Commit "Prepare the 0.5.0 release". Hand back to the maintainer before tagging `v0.5.0`.
