#!/usr/bin/env bash
# Private Linux renderer for the experimental DV build. Never installs system libraries.
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/../.." && pwd)
runtime_build="$repo_dir/build/dv-runtime"
runtime_prefix="$runtime_build/install"
mpv_commit=6a262d11f63733d37e3ee6d5fafcb1be1ef83952
placebo_commit=ccb74c6c13b1676e3af368cbe9281bfe649c17c6
mkdir -p "$runtime_build/src"

checkout() {
  local repository=$1 revision=$2 destination=$3
  if [[ ! -d "$destination/.git" ]]; then
    git init -q "$destination"
    git -C "$destination" remote add origin "$repository"
    git -C "$destination" fetch --depth 1 origin "$revision"
    git -C "$destination" checkout --detach FETCH_HEAD
  fi
  [[ "$(git -C "$destination" rev-parse HEAD)" == "$revision" ]] || {
    printf 'Source revision differs from the pinned runtime: %s\n' "$destination" >&2
    exit 1
  }
  git -C "$destination" diff --cached --quiet
}

checkout https://github.com/haasn/libplacebo.git "$placebo_commit" "$runtime_build/src/libplacebo"
git -C "$runtime_build/src/libplacebo" diff --quiet
git -C "$runtime_build/src/libplacebo" submodule update --init --recursive --depth 1
checkout https://github.com/mpv-player/mpv.git "$mpv_commit" "$runtime_build/src/mpv"
runtime_patch="$script_dir/mpv-dovi-residual-status.patch"
if git -C "$runtime_build/src/mpv" diff --quiet; then
  git -C "$runtime_build/src/mpv" apply "$runtime_patch"
fi
# Refuse unrelated source edits; allow this exact, recorded private property patch.
git -C "$runtime_build/src/mpv" diff --no-ext-diff --no-color --binary | cmp "$runtime_patch" -

setup_meson() {
  local build_dir=$1 source_dir=$2
  shift 2
  local reconfigure=()
  [[ ! -f "$build_dir/build.ninja" ]] || reconfigure+=(--reconfigure)
  env -u LD_PRELOAD meson setup "${reconfigure[@]}" "$build_dir" "$source_dir" \
    --prefix="$runtime_prefix" --libdir=lib --buildtype=release "$@"
}

setup_meson "$runtime_build/placebo-build" "$runtime_build/src/libplacebo" \
  -Ddefault_library=shared -Dvulkan=enabled -Dshaderc=enabled -Dglslang=disabled \
  -Dopengl=enabled -Ddovi=enabled -Dlibdovi=disabled -Ddemos=false -Dtests=true
env -u LD_PRELOAD ninja -C "$runtime_build/placebo-build" -j3
env -u LD_PRELOAD meson test -C "$runtime_build/placebo-build" --print-errorlogs --num-processes=2
env -u LD_PRELOAD meson install -C "$runtime_build/placebo-build"
export PKG_CONFIG_PATH="$runtime_prefix/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
setup_meson "$runtime_build/mpv-build" "$runtime_build/src/mpv" \
  -Dlibmpv=true -Dcplayer=true -Dtests=true -Dvulkan=enabled -Dwayland=enabled \
  -Dvaapi=enabled -Dpipewire=enabled -Dlua=enabled
env -u LD_PRELOAD ninja -C "$runtime_build/mpv-build" -j3
env -u LD_PRELOAD LD_LIBRARY_PATH="$runtime_prefix/lib" \
  meson test -C "$runtime_build/mpv-build" --print-errorlogs --num-processes=2
env -u LD_PRELOAD meson install -C "$runtime_build/mpv-build"
python3 "$script_dir/package_runtime.py" --runtime "$runtime_prefix" --manifest-only
printf 'Built private DV runtime: %s\n' "$runtime_prefix"
