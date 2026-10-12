#!/bin/sh
# Installs the latest Silage release:
#   curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
# Debian/Ubuntu get the .deb, Fedora the .rpm, Arch the AUR package (via
# paru or yay); anything else gets the portable build in ~/.local.
set -eu

repo="https://github.com/evman/silage/releases/latest/download"
say() { printf '\033[1m%s\033[0m\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1; }
sudo_() { if [ "$(id -u)" = 0 ]; then "$@"; else sudo "$@"; fi; }

id=""; like=""
if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  id=${ID:-}; like=${ID_LIKE:-}
fi
family=" $id $like "

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

case "$family" in
  *" arch "*|*" manjaro "*|*" endeavouros "*|*" cachyos "*)
    if need paru; then say "Installing silage-bin from the AUR with paru"; paru -S --needed silage-bin </dev/tty
    elif need yay; then say "Installing silage-bin from the AUR with yay"; yay -S --needed silage-bin </dev/tty
    else
      say "No AUR helper found; installing the portable build instead (or install paru/yay and run: paru -S silage-bin)"
      curl -fL --progress-bar -o "$tmp/silage.tar.gz" "$repo/silage-linux-x86_64.tar.gz"
      tar -xzf "$tmp/silage.tar.gz" -C "$tmp"
      sudo_ pacman -S --needed mpv </dev/tty
      "$tmp"/silage-*/install.sh
    fi ;;
  *" debian "*|*" ubuntu "*)
    say "Downloading the latest Silage .deb"
    curl -fL --progress-bar -o "$tmp/silage_amd64.deb" "$repo/silage_amd64.deb"
    chmod 644 "$tmp/silage_amd64.deb"
    sudo_ apt-get install -y "$tmp/silage_amd64.deb" ;;
  *" fedora "*|*" rhel "*|*" centos "*)
    say "Installing the latest Silage .rpm"
    sudo_ dnf install -y "$repo/silage.x86_64.rpm"
    say "Tip: for full H.264/HEVC support, install FFmpeg from RPM Fusion: https://rpmfusion.org/Howto/Multimedia" ;;
  *)
    say "Installing the portable Silage build into ~/.local (needs mpv 0.37+ with libmpv)"
    curl -fL --progress-bar -o "$tmp/silage.tar.gz" "$repo/silage-linux-x86_64.tar.gz"
    tar -xzf "$tmp/silage.tar.gz" -C "$tmp"
    "$tmp"/silage-*/install.sh ;;
esac

say "Done. Start Silage from your app menu, or run: silage"
