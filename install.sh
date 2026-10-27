#!/bin/sh
# Installs Silage from its signed repositories, so the system package manager keeps it up to date:
#   curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
# Debian/Ubuntu get the apt repository, Fedora/RHEL the dnf repository and Arch the pacman
# repository; anything else gets the portable build in ~/.local. Running it again is safe, and
# it moves an existing package install (a downloaded package or the AUR) onto the repository.
# For tests: SILAGE_REPO_BASE is where the apt and pacman repositories and the portable build
# are, SILAGE_RPM_REPO where the dnf repository is.
set -eu

repo=${SILAGE_REPO_BASE:-https://github.com/evman/silage/releases/latest/download}
repo=${repo%/}
rpm_repo=${SILAGE_RPM_REPO:-https://evman.github.io/silage/rpm}
rpm_repo=${rpm_repo%/}
# The Silage repository key (packaging/keys), embedded below.
fingerprint=DAA9CD6BF66D0D2AE50636BF4DBB1D66A1937A5F
apt_keyring=/usr/share/keyrings/silage-archive-keyring.gpg
rpm_key=/etc/pki/rpm-gpg/RPM-GPG-KEY-silage

say() { printf '\033[1m%s\033[0m\n' "$*"; }
sudo_() { if [ "$(id -u)" = 0 ]; then "$@"; else sudo "$@"; fi; }
# Piped into sh, stdin is this script: prompts read the terminal when there is
# one, and are auto-confirmed when there isn't (e.g. CI).
ask() { if (exec </dev/tty) 2>/dev/null; then "$@" </dev/tty; else "$@" --noconfirm; fi; }

# Portable first-launch setup and early development installs placed these
# root-owned files outside pacman's database. Let the package take ownership
# of those exact files, but never override files owned by another package.
arch_install() {
  for path in \
    /usr/libexec/silage-dv-helper/kms-snapshot \
    /usr/libexec/silage-dv-helper/silage-dv-helper \
    /usr/share/polkit-1/actions/dev.silage.dv-helper.policy; do
    if [ -f "$path" ] && [ ! -L "$path" ] && ! pacman -Qoq "$path" >/dev/null 2>&1; then
      say "Migrating existing helper file into package ownership: $path"
      set -- "$@" --overwrite "${path#/}"
    fi
  done
  ask "$@"
}

# Writes stdin to a root-owned, world-readable file.
put() { cat > "$tmp/put"; sudo_ install -D -m 644 "$tmp/put" "$1"; }

apt_repository() {
  say "Adding the Silage apt repository"
  silage_keyring | put "$apt_keyring"
  echo "deb [signed-by=$apt_keyring] $repo ./" | put /etc/apt/sources.list.d/silage.list
  sudo_ apt-get update
  sudo_ apt-get install -y silage
}

dnf_repository() {
  say "Adding the Silage dnf repository"
  silage_key_asc | put "$rpm_key"
  sudo_ rpm --import "$rpm_key"
  put /etc/yum.repos.d/silage.repo <<EOF
[silage]
name=Silage
baseurl=$rpm_repo/
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=file://$rpm_key
EOF
  # dnf install leaves an installed (older) silage as it is.
  if rpm -q silage >/dev/null 2>&1; then sudo_ dnf upgrade -y silage; else sudo_ dnf install -y silage; fi
  say "Tip: for full H.264/HEVC support, install FFmpeg from RPM Fusion: https://rpmfusion.org/Howto/Multimedia"
}

pacman_repository() {
  say "Adding the Silage pacman repository"
  # Creates the local signing key --lsign-key needs, when there isn't one yet (a no-op otherwise).
  sudo_ pacman-key --init
  silage_key_asc > "$tmp/silage.asc"
  sudo_ pacman-key --add "$tmp/silage.asc"
  sudo_ pacman-key --lsign-key "$fingerprint"
  if ! grep -q '^\[silage\]' /etc/pacman.conf; then
    printf '\n[silage]\nSigLevel = Required DatabaseRequired\nServer = %s\n' "$repo" | sudo_ tee -a /etc/pacman.conf >/dev/null
  fi
  # Arch only supports full upgrades (never -Sy alone), so this upgrades the system too.
  say "Installing silage-bin with a full system upgrade (pacman -Syu)"
  arch_install sudo_ pacman -Syu --needed silage-bin
}

# Everything runs from here, once sh has read the whole script, so nothing a command reads
# from stdin (the rest of the script, when piped) can cut it short.
main() {
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
    *" arch "*|*" manjaro "*|*" endeavouros "*|*" cachyos "*) pacman_repository ;;
    *" debian "*|*" ubuntu "*) apt_repository ;;
    *" fedora "*|*" rhel "*|*" centos "*) dnf_repository ;;
    *)
      say "Installing the portable Silage build into ~/.local (needs mpv 0.37+ with libmpv)"
      curl -fL --progress-bar -o "$tmp/silage.tar.gz" "$repo/silage-linux-x86_64.tar.gz"
      tar -xzf "$tmp/silage.tar.gz" -C "$tmp"
      sh "$tmp"/silage-*/install.sh ;;
  esac

  say "Done. Start Silage from your app menu, or run: silage"
}

# packaging/keys/silage-archive-keyring.asc
silage_key_asc() {
  cat <<'EOF'
-----BEGIN PGP PUBLIC KEY BLOCK-----

mQINBGrgcIYBEAD74AJQWGin9JXB8nIzXAVWCyfKzjX5RNHB41HEkiVRFMeCafAn
iEaLkpVgaUHub6k5zBx6FY23Ph4gBbPuwucvhBC3dD3Qj4n9wscE4L04JAPq9WfB
vErkAdJFAqrU7Z09z7h5G36a2doQcGZgC6IwfOWbr+fC4fM1z5e97BUJEHEABNfm
+Hychm1vutPKuJk1kS1i8csWGlIMWSEkoXNk6rK6pfOE06xhxCquTYfm8z9SIME5
/P9KyqMEwkeyubXHU1cmPnpQGvp2jbcbc1yEwjhj5tXaRv1uvQ4rmdJfVxBh//2p
Z28yJ1A9o6+gN1qaV+bCQQeeYSGtaW1vyV+SAaha+3dPjZtpruCoj3zGoiNs7hN4
wbUUWehdz50cRWcAJiMnSlgVRCZk9SIiGgOfwJmhjpAb1i1/KPiswM5IGEJUuftZ
gjKdxZXQl6WeA9dOx65NRuZIh44jMqPQBmL+dRrEhStAmaroa+TKxS5uUQ/xFP2Y
gIimau9hR/Ys1wfAhBJBn3jFuAB3dJMZBiaskDJFZJI91Ep9YxX4CsaeU0RP274V
3bIV5IRt4HsZtXke3JfR8Oi33324KTmJFCOKcPwnGRkSYYYvmBzk5dgc3WH5bOW5
VMwyM9ZbL1cA4OVpkCfe00TD5GHYqJb5pIWNQCEFWGvA6oVZLaD32Zu/VwARAQAB
tCRTaWxhZ2UgUmVsZWFzZXMgPGV2bWFuMzExQGdtYWlsLmNvbT6JAk4EEwEKADgW
IQTaqc1r9m0NKuUGNr9Nux1moZN6XwUCauBwhgIbAwULCQgHAgYVCgkICwIEFgID
AQIeAQIXgAAKCRBNux1moZN6XxbkEADOhoT9/ERa3y8Z5npxAls51EEkFbLMjwQ7
J5cObql0d6PjExq5jr7c8C7JtgjEHI2R2na0pVJq/xiRoqm1XMtDyjqqYqxPjmjp
o+LMjkYlBWj55fIllpN5gE7rRhmTCvt6EES/APDAm/clCmPunQKAEi+kxsdmDqG8
sobDhOyWWiiF6qaYKhLQeMjlGjF026HNF+/OFI8AD1M0TaC7sClbRmIdNNGW8Gwy
gZKWRNjUQPwCkD4g7Jblhxite7lurDgRDMfj1esgL+VCMFY7GIC0js5e7c2pfgDd
TN+Q/eAbh4o4cOa0iweUCnGBuCykRiT0fSiqgAScuAsOhbMFfY5sNY6R+PTB1sNe
AflihlwPUpZ1q1LJTR4a8GhdEdfS0LFBcQwd0IPw3YeEANnNJzz8yEpF7fGIt9+c
oFtD3+pknMB5cME627Zxs195YI+ultI17tJXQCgU8+zE+1lwdywntbjFuCDuRI6V
BCWa9JFjGvojk9pwB7BXL1Es9FaDTb5uOB3LlJKIiJc5fLORy/P/c65ElRK6/d/Q
H11/xOkGrkVIDR8MJuhw2PxbZm8ichRufBcK4AVa8jfdd2xiBZKXTVH85i04QEPR
yAt2qdAzYf+eOh3fgte30dxt6rax+8H8mSIgUP6hhVoQPx2mi8XvfvVm8A4SBePI
We3IDYC5Uw==
=F2b0
-----END PGP PUBLIC KEY BLOCK-----
EOF
}

# packaging/keys/silage-archive-keyring.gpg
silage_keyring() {
  base64 -d <<'EOF'
mQINBGrgcIYBEAD74AJQWGin9JXB8nIzXAVWCyfKzjX5RNHB41HEkiVRFMeCafAniEaLkpVgaUHu
b6k5zBx6FY23Ph4gBbPuwucvhBC3dD3Qj4n9wscE4L04JAPq9WfBvErkAdJFAqrU7Z09z7h5G36a
2doQcGZgC6IwfOWbr+fC4fM1z5e97BUJEHEABNfm+Hychm1vutPKuJk1kS1i8csWGlIMWSEkoXNk
6rK6pfOE06xhxCquTYfm8z9SIME5/P9KyqMEwkeyubXHU1cmPnpQGvp2jbcbc1yEwjhj5tXaRv1u
vQ4rmdJfVxBh//2pZ28yJ1A9o6+gN1qaV+bCQQeeYSGtaW1vyV+SAaha+3dPjZtpruCoj3zGoiNs
7hN4wbUUWehdz50cRWcAJiMnSlgVRCZk9SIiGgOfwJmhjpAb1i1/KPiswM5IGEJUuftZgjKdxZXQ
l6WeA9dOx65NRuZIh44jMqPQBmL+dRrEhStAmaroa+TKxS5uUQ/xFP2YgIimau9hR/Ys1wfAhBJB
n3jFuAB3dJMZBiaskDJFZJI91Ep9YxX4CsaeU0RP274V3bIV5IRt4HsZtXke3JfR8Oi33324KTmJ
FCOKcPwnGRkSYYYvmBzk5dgc3WH5bOW5VMwyM9ZbL1cA4OVpkCfe00TD5GHYqJb5pIWNQCEFWGvA
6oVZLaD32Zu/VwARAQABtCRTaWxhZ2UgUmVsZWFzZXMgPGV2bWFuMzExQGdtYWlsLmNvbT6JAk4E
EwEKADgWIQTaqc1r9m0NKuUGNr9Nux1moZN6XwUCauBwhgIbAwULCQgHAgYVCgkICwIEFgIDAQIe
AQIXgAAKCRBNux1moZN6XxbkEADOhoT9/ERa3y8Z5npxAls51EEkFbLMjwQ7J5cObql0d6PjExq5
jr7c8C7JtgjEHI2R2na0pVJq/xiRoqm1XMtDyjqqYqxPjmjpo+LMjkYlBWj55fIllpN5gE7rRhmT
Cvt6EES/APDAm/clCmPunQKAEi+kxsdmDqG8sobDhOyWWiiF6qaYKhLQeMjlGjF026HNF+/OFI8A
D1M0TaC7sClbRmIdNNGW8GwygZKWRNjUQPwCkD4g7Jblhxite7lurDgRDMfj1esgL+VCMFY7GIC0
js5e7c2pfgDdTN+Q/eAbh4o4cOa0iweUCnGBuCykRiT0fSiqgAScuAsOhbMFfY5sNY6R+PTB1sNe
AflihlwPUpZ1q1LJTR4a8GhdEdfS0LFBcQwd0IPw3YeEANnNJzz8yEpF7fGIt9+coFtD3+pknMB5
cME627Zxs195YI+ultI17tJXQCgU8+zE+1lwdywntbjFuCDuRI6VBCWa9JFjGvojk9pwB7BXL1Es
9FaDTb5uOB3LlJKIiJc5fLORy/P/c65ElRK6/d/QH11/xOkGrkVIDR8MJuhw2PxbZm8ichRufBcK
4AVa8jfdd2xiBZKXTVH85i04QEPRyAt2qdAzYf+eOh3fgte30dxt6rax+8H8mSIgUP6hhVoQPx2m
i8XvfvVm8A4SBePIWe3IDYC5Uw==
EOF
}

main "$@"
