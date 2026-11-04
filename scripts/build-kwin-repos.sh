#!/usr/bin/env bash
# The opt-in Silage KWin repositories (Dolby Vision; docs/superpowers/specs/2026-11-04-kwin-dolby-vision-design.md),
# signed with the same key as Silage's own (REPO_GPG_KEY_ID, already imported):
#
#   arch   PKG_DIR OUT   signs each kwin*.pkg.tar.zst and builds silage-kwin.db/.files (+.sig)
#                        in OUT, for the release tag kwin-arch (pacman Server = its download URL).
#   arch-empty - OUT     an empty, signed silage-kwin.db/.files: pacman takes a package from the first
#                        repository that has one, so while Arch's kwin is newer than our build the
#                        repository must offer nothing (systems then run the stock kwin).
#   fedora RPM_DIR PAGES  dnf repodata for the RPMs on the release tag kwin-fedora, signed, plus
#                        silage-kwin.repo and the key, in PAGES/kwin-rpm (GitHub Pages).
#
# Needs gpg plus repo-add (arch) or createrepo_c (fedora).
set -euo pipefail
kind=${1:?usage: $0 arch|arch-empty|fedora DIR OUT}; dir=$(realpath "${2:?dir}"); out=$(realpath -m "${3:?out}")
: "${REPO_GPG_KEY_ID:?REPO_GPG_KEY_ID must name the imported repository key}"
sign=(gpg --batch --yes --local-user "$REPO_GPG_KEY_ID" --digest-algo SHA512)
mkdir -p "$out"

case "$kind" in
arch-empty)
    work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
    for db in db files; do
        tar -czf "$work/silage-kwin.$db" -T /dev/null
        "${sign[@]}" --detach-sign -o "$work/silage-kwin.$db.sig" "$work/silage-kwin.$db"
        cp "$work/silage-kwin.$db" "$work/silage-kwin.$db.sig" "$out/"
    done
    ;;
arch)
    shopt -s nullglob
    pkgs=("$dir"/kwin*.pkg.tar.zst)
    [ ${#pkgs[@]} -gt 0 ] || { echo "no kwin packages in $dir" >&2; exit 2; }
    work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
    for p in "${pkgs[@]}"; do
        cp "$p" "$work/"
        "${sign[@]}" --detach-sign -o "$work/$(basename "$p").sig" "$work/$(basename "$p")"
    done
    (cd "$work" && repo-add --quiet --sign --key "$REPO_GPG_KEY_ID" silage-kwin.db.tar.gz ./*.pkg.tar.zst)
    # Release assets can't be symlinks: the database under its plain names.
    for db in db files; do
        cp "$work/silage-kwin.$db.tar.gz" "$out/silage-kwin.$db"
        cp "$work/silage-kwin.$db.tar.gz.sig" "$out/silage-kwin.$db.sig"
    done
    cp "$work"/*.pkg.tar.zst "$work"/*.pkg.tar.zst.sig "$out/"
    ;;
fedora)
    repo_url="https://evman.github.io/silage/kwin-rpm"
    rpm_base="${SILAGE_KWIN_RPM_URL:-https://github.com/evman/silage/releases/download/kwin-fedora}/"
    work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
    cp "$dir"/*.rpm "$work/"
    createrepo_c --quiet --baseurl "$rpm_base" "$work"
    rm -rf "$out/kwin-rpm"; mkdir -p "$out/kwin-rpm"
    cp -r "$work/repodata" "$out/kwin-rpm/"
    "${sign[@]}" --armor --detach-sign -o "$out/kwin-rpm/repodata/repomd.xml.asc" "$out/kwin-rpm/repodata/repomd.xml"
    gpg --batch --armor --export "$REPO_GPG_KEY_ID" > "$out/kwin-rpm/silage.asc"
    cat > "$out/kwin-rpm/silage-kwin.repo" <<EOF
[silage-kwin]
name=Silage KWin (Dolby Vision)
baseurl=$repo_url/
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=$repo_url/silage.asc
EOF
    ;;
*) echo "unknown kind: $kind" >&2; exit 2 ;;
esac
