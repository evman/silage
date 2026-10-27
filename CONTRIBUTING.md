# Contributing to Silage

Linux and Windows share one codebase on `main`. The former Windows port branch
has been merged; use `main` for both platforms.

## Get the source

```sh
git clone https://github.com/evman/silage.git
cd silage
git switch -c your-change
```

For an existing checkout, commit your local work on a topic branch, then fetch
and update `main` with `git pull --ff-only origin main`. Start new work from
that updated branch. Submit a pull request targeting `main` with a description
of the change and its validation.

## Build and test

Linux needs JDK 21, libmpv 0.37+, ffmpeg and a display. Desktop integration tests
also need `dbus-daemon` and use a private bus. Run:

```sh
./gradlew :app:test :player:test :shared:desktopTest
./gradlew :app:run
```

On Windows, prepare JDK 21 and bundled mpv as described in [WINDOWS.md](WINDOWS.md),
then use `test-windows.cmd` and `build-windows.cmd :app:run`. Packaging and
installer regression tests run on either platform:

```sh
python -m unittest discover -s scripts/tests -v
```

Linux helper/bootstrap and lab regressions run without privileged hardware access:

```sh
python3 -m unittest discover -s scripts/dv-helper -v
python3 -m unittest discover -s scripts/dv-lab -v
bash scripts/dv-helper/install.sh --stage
```

Only explicitly enabled native hardware tests write display signaling. Never run
the application, mpv or FFmpeg as root. See [the DV guide](DOLBY_VISION.md) and
[runtime build instructions](scripts/dv-helper/BUILDING-RUNTIME.md).

Keep server features in shared code and adapt desktop services where needed.
Preserve Linux Wayland/HDR output and Windows D3D11 playback, and never commit
credentials, personal settings, extracted runtimes or dependency binaries.

## Releases

Update README, [LINUX.md](LINUX.md), [WINDOWS.md](WINDOWS.md), AppStream release
metadata and `packaging/release-notes.md` before tagging a release. Set
`-PappVersion=<version>` when packaging locally so the version shown in About
matches the filenames.
Update [CHANGELOG.md](CHANGELOG.md), the DV setup/limitations and runtime source
information when changing those components.

A `v<version>` tag on `main` runs `.github/workflows/release.yml`. It tests Linux
and Windows; builds the Linux tarball, DEB, RPM and Arch package and the Windows
installer (`Silage-Setup.exe`); signs `SHA256SUMS` and the apt, dnf and pacman
repositories; tests installing and upgrading from those repositories on each
distro and on Windows; and only then marks the release Latest. The full
procedure, keys and post-release checks are in [docs/RELEASING.md](docs/RELEASING.md).

The release workflow first builds/tests the pinned private playback runtime in
Arch Linux. Its verified libraries and licenses are included in each Linux app
image, along with the helper and its portable setup. System packages also install
the fixed root-owned helper and polkit policy. Incompatible runtime dependencies
fall back to distro libmpv. Publish the generated corresponding-source tarball
and retain all library/source hashes and license files. Source archives include
vendored runtime submodules and the applied Silage patch; no private media or
authentication data belongs in an artifact.

A local build alone does not publish a release. Historical releases keep their
original artifacts and version-specific notes.
