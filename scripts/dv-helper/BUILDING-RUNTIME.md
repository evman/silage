# Corresponding playback runtime sources

The release's `silage-playback-runtime-sources.tar.gz` includes the exact mpv and
libplacebo trees used for the bundled libraries, including vendored submodules,
licenses, the applied Silage patch, build scripts and a revision/hash manifest.
The mpv source in this archive already has `mpv-dovi-residual-status.patch` applied.
Do not apply it a second time.

The original CI build uses current Arch Linux x86_64 with these packages:

```sh
pacman -S --needed base-devel git meson ninja python pkgconf mpv shaderc \
  vulkan-headers vulkan-icd-loader vulkan-swrast wayland-protocols \
  libpipewire luajit patchelf
```

Build directly from the extracted source trees without Git metadata:

```sh
runtime_prefix="$PWD/install"
meson setup placebo-build libplacebo --prefix="$runtime_prefix" --libdir=lib \
  --buildtype=release -Ddefault_library=shared -Dvulkan=enabled -Dshaderc=enabled \
  -Dglslang=disabled -Dopengl=enabled -Ddovi=enabled -Dlibdovi=disabled \
  -Ddemos=false -Dtests=true
ninja -C placebo-build -j3
meson test -C placebo-build --print-errorlogs --num-processes=2
meson install -C placebo-build
export PKG_CONFIG_PATH="$runtime_prefix/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
meson setup mpv-build mpv --prefix="$runtime_prefix" --libdir=lib \
  --buildtype=release -Dlibmpv=true -Dcplayer=true -Dtests=true \
  -Dvulkan=enabled -Dwayland=enabled -Dvaapi=enabled -Dpipewire=enabled -Dlua=enabled
ninja -C mpv-build -j3
LD_LIBRARY_PATH="$runtime_prefix/lib" meson test -C mpv-build --print-errorlogs --num-processes=2
meson install -C mpv-build
```

Alternatively, check out the matching Silage release and run
`bash scripts/dv-helper/build_runtime.sh`. That script fetches the pinned Git
revisions, verifies the exact patch, and performs the same build and tests.
The app source and remaining build/package scripts are included in GitHub's
source archives for that release.

The bundled libraries use `$ORIGIN` lookup for their private libplacebo. They
still depend on host graphics/audio libraries and the host FFmpeg ABI. The app
tries this runtime first and falls back to the distribution's libmpv if loading
it fails. This is separate from experimental display signaling and does not
establish complete Dolby content-mapping or measured color accuracy.
