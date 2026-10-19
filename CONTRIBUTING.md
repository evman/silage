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
then use `test-windows.cmd` and `build-windows.cmd :app:run`. Portable packaging
regression tests run on either platform:

```sh
python -m unittest discover -s scripts/tests -v
```

Keep server features in shared code and adapt desktop services where needed.
Preserve Linux Wayland/HDR output and Windows D3D11 playback, and never commit
credentials, personal settings, extracted runtimes or dependency binaries.

## Releases

Update README, [LINUX.md](LINUX.md), [WINDOWS.md](WINDOWS.md), AppStream release
metadata and `packaging/release-notes.md` before tagging a release. Set
`-PappVersion=<version>` when packaging locally so the version shown in About
matches the filenames.

A `v<version>` tag on `main` runs `.github/workflows/release.yml`. It tests Linux
and Windows, builds the Linux tarball/DEB/RPM/Arch package and Windows portable
EXE/ZIP, and checks package installation before publishing. The release includes
versioned artifacts, stable download filenames, SHA256SUMS and a checksum-pinned
Arch PKGBUILD. Post-publication jobs verify the documented Linux installer and
the Windows single-file download. Keep the repository's Arch PKGBUILD checksum
in sync with the published tarball after the build completes.

A local build alone does not publish a release. Historical releases keep their
original artifacts and version-specific notes.
