#!/usr/bin/env bash
# Turns Arch's own kwin recipe into the Silage Dolby Vision build: the same
# sources, dependencies and build, plus packaging/kwin/silage-dv-<major.minor>.patch,
# with the release raised to <Arch's>.99 so it's newer than Arch's (and CachyOS's
# rebuilds) of the same version. When Arch ships a newer kwin, the stock one is
# newer again until this is rebuilt: systems update normally and fall back.
#
# Usage: packaging/kwin/arch/prepare.sh <pkgver> <pkgrel> <out dir>
# Then: cd <out dir> && makepkg --skippgpcheck   (the sources' sha256 sums are Arch's)
set -euo pipefail
pkgver=${1:?pkgver}; pkgrel=${2:?pkgrel}; out=${3:?out dir}
here=$(cd "$(dirname "$0")" && pwd)
minor=$(echo "$pkgver" | cut -d. -f1-2)
patch="$here/../silage-dv-$minor.patch"
[ -f "$patch" ] || { echo "No Silage patch for KWin $minor (packaging/kwin/silage-dv-$minor.patch)" >&2; exit 3; }

mkdir -p "$out"
curl -fsSL -o "$out/PKGBUILD" "https://gitlab.archlinux.org/archlinux/packaging/packages/kwin/-/raw/$pkgver-$pkgrel/PKGBUILD"
grep -q "^pkgver=$pkgver$" "$out/PKGBUILD" && grep -q "^pkgrel=$pkgrel$" "$out/PKGBUILD" \
    || { echo "Arch's recipe at $pkgver-$pkgrel doesn't describe that version" >&2; exit 4; }
cp "$patch" "$out/silage-dv.patch"
sum=$(sha256sum "$out/silage-dv.patch" | cut -d' ' -f1)

# Silage's patch first in source/sha256sums, applied first in prepare().
sed -i \
    -e "s/^pkgrel=$pkgrel$/pkgrel=$pkgrel.99/" \
    -e "s/^pkgdesc='\(.*\)'$/pkgdesc='\1 (with Silage Dolby Vision support)'/" \
    -e "s/^source=(/source=(silage-dv.patch\n  /" \
    -e "s/^sha256sums=(/sha256sums=('$sum'\n            /" \
    "$out/PKGBUILD"
if grep -q '^prepare() {' "$out/PKGBUILD"; then
    sed -i 's/^prepare() {$/prepare() {\n  patch -d $pkgname-$pkgver -p1 < silage-dv.patch # Silage Dolby Vision lease/' "$out/PKGBUILD"
else
    printf '\nprepare() {\n  patch -d $pkgname-$pkgver -p1 < silage-dv.patch # Silage Dolby Vision lease\n}\n' >> "$out/PKGBUILD"
fi
grep -q "silage-dv.patch # Silage" "$out/PKGBUILD" && grep -q "^pkgrel=$pkgrel.99$" "$out/PKGBUILD" \
    || { echo "Couldn't adapt Arch's recipe" >&2; exit 5; }
echo "Prepared kwin $pkgver-$pkgrel.99 in $out"
