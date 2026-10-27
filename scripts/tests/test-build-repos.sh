#!/usr/bin/env bash
# scripts/tests/test-build-repos.sh: build-repos.sh turns dummy packages into signed apt, pacman
# and dnf repositories. Uses a throwaway GPG key generated here; never the real repository key.
# Needs gpg, apt-ftparchive, repo-add, bsdtar, createrepo_c, rpmkeys and nfpm ($NFPM or on PATH):
# the CI build job runs it in an archlinux container with apt, createrepo_c and rpm-tools added.
# KEEP_REPOS=DIR keeps the release assets (DIR/release), the Pages tree (DIR/pages) and the test
# key (DIR/keys/silage-archive-keyring.{gpg,asc}) for repo-install-test.sh; with SILAGE_RELEASE_URL
# set to where DIR/release will be served, the dnf metadata points there.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
nfpm=${NFPM:-nfpm}
version=0.5.0
tag="v$version"

tmp=$(mktemp -d)
export GNUPGHOME="$tmp/gnupg"
trap 'gpgconf --kill all 2>/dev/null || true; rm -rf "$tmp"' EXIT
mkdir -m 700 "$GNUPGHOME"

fail() { echo "FAIL: $*" >&2; exit 1; }

gpg --batch --quiet --passphrase '' --quick-gen-key 'Silage Test Repository <test@silage.invalid>' rsa3072 sign never 2>/dev/null
fpr=$(gpg --with-colons --list-secret-keys 2>/dev/null | awk -F: '/^fpr/ {print $10; exit}')
gpg --batch --armor --export-secret-keys "$fpr" > "$tmp/repo-private.asc"

# Dummy content laid out for the real packaging/nfpm.yaml, so its rpm signing is what's tested.
src="$tmp/src"
mkdir -p "$src/dist/app/bin" "$src/packaging/icons/hicolor/48x48/apps" \
  "$src/build/dv-helper/stage/usr/libexec/silage-dv-helper" "$src/scripts/dv-helper"
printf '#!/bin/sh\necho "Silage %s"\n' "$version" > "$src/dist/app/bin/silage"
cp "$src/dist/app/bin/silage" "$src/build/dv-helper/stage/usr/libexec/silage-dv-helper/silage-dv-helper"
chmod 755 "$src/dist/app/bin/silage" "$src/build/dv-helper/stage/usr/libexec/silage-dv-helper/silage-dv-helper"
for f in packaging/dev.silage.Silage.desktop packaging/dev.silage.Silage.metainfo.xml \
  packaging/icons/hicolor/48x48/apps/dev.silage.Silage.png LICENSE scripts/dv-helper/dev.silage.dv-helper.policy; do
  echo dummy > "$src/$f"
done
cp "$root/packaging/nfpm.yaml" "$src/nfpm.yaml"
cat > "$src/pacman.yaml" <<'EOF'
name: silage-bin
arch: amd64
platform: linux
version: ${SILAGE_VERSION}
release: 1
maintainer: test <test@silage.invalid>
description: dummy
contents:
  - src: dist/app/
    dst: /opt/silage
    type: tree
  - src: /opt/silage/bin/silage
    dst: /usr/bin/silage
    type: symlink
  - src: build/dv-helper/stage/usr/libexec/silage-dv-helper/
    dst: /usr/libexec/silage-dv-helper
    type: tree
EOF

dist="$tmp/dist"
mkdir "$dist"
(
  cd "$src"
  export SILAGE_VERSION=$version
  SILAGE_RPM_SIGNING_KEY_FILE="$tmp/repo-private.asc" "$nfpm" pkg -f nfpm.yaml -p rpm -t "$dist/"
  "$nfpm" pkg -f nfpm.yaml -p deb -t "$dist/"
  "$nfpm" pkg -f pacman.yaml -p archlinux -t "$dist/"
) >/dev/null
deb="silage_${version}_amd64.deb"
rpm="silage-${version}-1.x86_64.rpm"
pkg="silage-bin-${version}-1-x86_64.pkg.tar.zst"
for f in "$deb" "$rpm" "$pkg"; do [[ -f "$dist/$f" ]] || fail "nfpm did not make $f"; done
# The release keeps version-free copies beside them; the indexes must not list those twice.
cp "$dist/$deb" "$dist/silage_amd64.deb"
cp "$dist/$rpm" "$dist/silage.x86_64.rpm"
cp "$dist/$pkg" "$dist/silage-bin-x86_64.pkg.tar.zst"

if (unset REPO_GPG_KEY_ID; "$root/scripts/build-repos.sh" "$dist" "$version" "$tmp/pages") >/dev/null 2>&1; then
  fail "built repositories without REPO_GPG_KEY_ID"
fi
REPO_GPG_KEY_ID=$fpr "$root/scripts/build-repos.sh" "$dist" "$version" "$tmp/pages"

verify() { gpg --batch --quiet --verify "$@" 2>/dev/null || fail "gpg --verify $*"; }

# apt: a flat repository whose Release is signed both ways and covers the package index.
cd "$dist"
verify InRelease
verify Release.gpg Release
[[ "$(grep -c '^Package: silage$' Packages)" -eq 1 ]] || fail "Packages should list silage exactly once"
grep -qx "Filename: ./$deb" Packages || fail "Packages does not point at ./$deb"
[[ "$(gzip -dc Packages.gz)" == "$(<Packages)" ]] || fail "Packages.gz differs from Packages"
for f in Packages Packages.gz; do
  sum=$(sha256sum "$f" | cut -d' ' -f1)
  sed -n '/^SHA256:/,/^[^ ]/p' InRelease | grep -q " $sum .* $f$" || fail "InRelease lacks the SHA256 of $f"
done

# pacman: real files (not symlinks) with signatures; the db lists silage-bin; the package is signed.
for f in silage.db silage.files; do
  [[ -f "$f" && ! -L "$f" ]] || fail "$f is not a regular file"
  verify "$f.sig" "$f"
done
bsdtar -tf silage.db | grep -qx "silage-bin-$version-1/desc" || fail "silage.db does not list silage-bin"
desc=$(bsdtar -xOf silage.db "silage-bin-$version-1/desc")
grep -qx "$pkg" <<< "$desc" || fail "silage.db does not point at $pkg"
# pacman fetches the package signature from beside the package (repo-add no longer embeds it).
verify "$pkg.sig" "$pkg"
[[ ! -e silage.db.tar.gz && ! -e silage.files.tar.gz ]] || fail "intermediate repo-add files left in the release"

# dnf: signed repodata pointing at the tagged release, the public key and the .repo file.
rpmdir="$tmp/pages/rpm"
verify "$rpmdir/repodata/repomd.xml.asc" "$rpmdir/repodata/repomd.xml"
primary=$(find "$rpmdir/repodata" -name '*-primary.xml*')
[[ -n "$primary" ]] || fail "no primary metadata"
location=$(bsdcat "$primary" | grep -o '<location [^>]*>')
release_url=${SILAGE_RELEASE_URL:-https://github.com/evman/silage/releases/download/$tag}
[[ "$location" == *"xml:base=\"$release_url/\""* && "$location" == *"href=\"$rpm\""* ]] \
  || fail "primary metadata points elsewhere: $location"
[[ "$(gpg --show-keys --with-colons "$rpmdir/silage.asc" | awk -F: '/^fpr/ {print $10; exit}')" == "$fpr" ]] \
  || fail "silage.asc is not the repository key"
expected_repo='[silage]
name=Silage
baseurl=https://evman.github.io/silage/rpm/
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=https://evman.github.io/silage/rpm/silage.asc'
[[ "$(<"$rpmdir/silage.repo")" == "$expected_repo" ]] || fail "unexpected silage.repo: $(<"$rpmdir/silage.repo")"

# The rpm itself is signed with the repository key.
rpmkeys --dbpath "$tmp/rpmdb" --import "$rpmdir/silage.asc"
check=$(rpmkeys --dbpath "$tmp/rpmdb" -K "$rpm")
[[ "$check" == *"signatures OK"* ]] || fail "rpm -K: $check"

if [[ -n "${KEEP_REPOS:-}" ]]; then
  mkdir -p "$KEEP_REPOS/keys"
  cp -r "$dist" "$KEEP_REPOS/release"
  cp -r "$tmp/pages" "$KEEP_REPOS/pages"
  gpg --batch --export "$fpr" > "$KEEP_REPOS/keys/silage-archive-keyring.gpg"
  gpg --batch --armor --export "$fpr" > "$KEEP_REPOS/keys/silage-archive-keyring.asc"
fi

echo "test-build-repos: OK"
