#!/usr/bin/env bash
# scripts/build-repos.sh DIST_DIR VERSION OUT_PAGES_DIR: builds the signed Silage repositories.
#  - apt (flat) and pacman indexes go into DIST_DIR, beside the packages, as release assets;
#  - the dnf repodata, its key and silage.repo go into OUT_PAGES_DIR/rpm (GitHub Pages).
# Signs with the GPG key $REPO_GPG_KEY_ID, already in the keyring. Needs apt-ftparchive,
# repo-add, createrepo_c and gpg (the release job runs it in an archlinux container).
# SILAGE_RELEASE_URL overrides where the dnf metadata says the packages are (local tests).
set -euo pipefail
dist=${1:?usage: scripts/build-repos.sh DIST_DIR VERSION OUT_PAGES_DIR}
version=${2:?usage: scripts/build-repos.sh DIST_DIR VERSION OUT_PAGES_DIR}
pages=${3:?usage: scripts/build-repos.sh DIST_DIR VERSION OUT_PAGES_DIR}
: "${REPO_GPG_KEY_ID:?REPO_GPG_KEY_ID (repository GPG key fingerprint) is not set}"
dist=$(realpath "$dist")
mkdir -p "$pages"
pages=$(realpath "$pages")

# Release asset names; pacman versions can't contain '-', so a prerelease's becomes '_'.
deb="silage_${version}_amd64.deb"
rpm="silage-${version}-1.x86_64.rpm"
pkg="silage-bin-${version//-/_}-1-x86_64.pkg.tar.zst"
for f in "$deb" "$rpm" "$pkg"; do
  [[ -f "$dist/$f" ]] || { echo "Missing package: $dist/$f" >&2; exit 1; }
done
rpm_baseurl="${SILAGE_RELEASE_URL:-https://github.com/evman/silage/releases/download/v$version}/"
rpm_repo="https://evman.github.io/silage/rpm"

sign=(gpg --batch --yes --local-user "$REPO_GPG_KEY_ID" --digest-algo SHA512)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# apt: a flat repository (deb [signed-by=…] <release download URL> ./). Index only the
# versioned .deb, not its version-free copy, so Packages lists one silage.
mkdir "$work/apt"
cp "$dist/$deb" "$work/apt/"
(
  cd "$work/apt"
  apt-ftparchive packages . > Packages
  gzip -9nk Packages
  rm "$deb"
  apt-ftparchive -o APT::FTPArchive::Release::Origin=Silage -o APT::FTPArchive::Release::Label=Silage \
    -o APT::FTPArchive::Release::Architectures=amd64 release . > "$work/Release"
  mv "$work/Release" Release
  "${sign[@]}" --clearsign -o InRelease Release
  "${sign[@]}" --armor --detach-sign -o Release.gpg Release
  cp Packages Packages.gz Release InRelease Release.gpg "$dist/"
)

# pacman: the signed package plus silage.db/silage.files and their signatures. Release assets
# can't be symlinks, so the .tar.gz files repo-add makes are copied to their plain names.
mkdir "$work/pacman"
cp "$dist/$pkg" "$work/pacman/"
(
  cd "$work/pacman"
  "${sign[@]}" --detach-sign -o "$pkg.sig" "$pkg"
  repo-add --quiet --sign --key "$REPO_GPG_KEY_ID" silage.db.tar.gz "$pkg"
  for db in db files; do
    cp silage.$db.tar.gz "$dist/silage.$db"
    cp silage.$db.tar.gz.sig "$dist/silage.$db.sig"
  done
  cp "$pkg.sig" "$dist/"
)

# dnf: repodata pointing at this release's download URL, signed, with the key and a .repo file.
mkdir "$work/rpm"
cp "$dist/$rpm" "$work/rpm/"
createrepo_c --quiet --baseurl "$rpm_baseurl" "$work/rpm"
rm -rf "$pages/rpm"
mkdir -p "$pages/rpm"
cp -r "$work/rpm/repodata" "$pages/rpm/"
"${sign[@]}" --armor --detach-sign -o "$pages/rpm/repodata/repomd.xml.asc" "$pages/rpm/repodata/repomd.xml"
gpg --batch --armor --export "$REPO_GPG_KEY_ID" > "$pages/rpm/silage.asc"
cat > "$pages/rpm/silage.repo" <<EOF
[silage]
name=Silage
baseurl=$rpm_repo/
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=$rpm_repo/silage.asc
EOF
