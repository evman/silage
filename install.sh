#!/bin/sh
# Installs the latest Silage release:
#   curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
# Debian/Ubuntu get the .deb, Fedora the .rpm, Arch the AUR package (paru or
# yay) or the release's pacman package; anything else gets the portable
# build in ~/.local.
set -eu

repo="https://github.com/evman/silage/releases/latest/download"
say() { printf '\033[1m%s\033[0m\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1; }
sudo_() { if [ "$(id -u)" = 0 ]; then "$@"; else sudo "$@"; fi; }
# Piped into sh, stdin is this script: prompts read the terminal when there is
# one, and are auto-confirmed when there isn't (e.g. CI).
ask() { if (exec </dev/tty) 2>/dev/null; then "$@" </dev/tty; else "$@" --noconfirm; fi; }

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
    # The AUR package when it exists and an AUR helper is installed; otherwise
    # the same package from the GitHub release, installed with pacman.
    if curl -fsS "https://aur.archlinux.org/rpc/v5/info?arg[]=silage-bin" 2>/dev/null | grep -q '"resultcount":1' \
       && { need paru || need yay; }; then
      helper=$(need paru && echo paru || echo yay)
      say "Installing silage-bin from the AUR with $helper"
      ask "$helper" -S --needed silage-bin
    else
      say "Downloading the latest Silage package for Arch"
      curl -fL --progress-bar -o "$tmp/silage-bin-x86_64.pkg.tar.zst" "$repo/silage-bin-x86_64.pkg.tar.zst"
      ask sudo_ pacman -U --needed "$tmp/silage-bin-x86_64.pkg.tar.zst"
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
    sh "$tmp"/silage-*/install.sh ;;
esac

say "Done. Start Silage from your app menu, or run: silage"
