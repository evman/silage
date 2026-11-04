#!/usr/bin/env bash
# Exercises packaging/kwin/silage-kwin-dv against a staged fake system: stub pacman, pacman-key,
# dnf, rpm and curl record what they're asked to do. Never touches the real system.
set -euo pipefail
# As root the helper ignores SILAGE_KWIN_ROOT (a test must never fake a system), so these tests
# can't run as root: drop to nobody, which is all they need.
if [ "$(id -u)" = 0 ]; then
  command -v setpriv >/dev/null || { echo "Run these tests as a regular user (setpriv not found)"; exit 1; }
  exec setpriv --reuid=65534 --regid=65534 --clear-groups --reset-env bash "$(realpath "$0")" "$@"
fi
helper=$(cd "$(dirname "$0")/../.." && pwd)/packaging/kwin/silage-kwin-dv
fails=0
base_path=$PATH

setup() {  # setup DISTRO KWIN_VERSION [monitor|nomonitor]
  t=$(mktemp -d); export SILAGE_KWIN_ROOT=$t
  mkdir -p "$t/etc" "$t/bin" "$t/sys/class/drm/card1-HDMI-A-1"
  case $1 in
    arch) printf 'ID=cachyos\nID_LIKE=arch\n' > "$t/etc/os-release"
          printf '[options]\nArchitecture = auto\n\n[core]\nInclude = /etc/pacman.d/mirrorlist\n\n[extra]\nInclude = /etc/pacman.d/mirrorlist\n' > "$t/etc/pacman.conf" ;;
    fedora) printf 'ID=fedora\n' > "$t/etc/os-release" ;;
    other) printf 'ID=ubuntu\nID_LIKE=debian\n' > "$t/etc/os-release" ;;
  esac
  echo "$2" > "$t/kwin-version"
  printf 'key' > "$t/key.asc"
  # A PG32UCDM-like EDID with the verified Dolby Vision block, or one without it.
  if [ "${3:-monitor}" = monitor ]; then
    printf '\x00\xff\xff\x01\x46\xd0\x00\x4c\x02\x5d\x89\x60\x6f\x87\x00' > "$t/sys/class/drm/card1-HDMI-A-1/edid"
  else
    printf '\x00\xff\xff\x01\x46\xd0\x00\x00\x00' > "$t/sys/class/drm/card1-HDMI-A-1/edid"
  fi
  cat > "$t/bin/pacman" <<'EOF'
#!/bin/sh
echo "pacman $*" >> "$SILAGE_KWIN_ROOT/calls"
case $1 in
  -Q) v=$(cat "$SILAGE_KWIN_ROOT/kwin-version"); [ -n "$v" ] && echo "kwin $v" || exit 1 ;;
  -Si) echo "Version         : $(cat "$SILAGE_KWIN_ROOT/stock-version" 2>/dev/null || echo 6.8.2-2.1)" ;;
  -U) echo 6.8.2-2.99 > "$SILAGE_KWIN_ROOT/kwin-version" ;;
  -S) cat "$SILAGE_KWIN_ROOT/stock-version" > "$SILAGE_KWIN_ROOT/kwin-version" 2>/dev/null || echo 6.8.2-2.1 > "$SILAGE_KWIN_ROOT/kwin-version" ;;
esac
EOF
  cat > "$t/bin/pacman-key" <<'EOF'
#!/bin/sh
echo "pacman-key $*" >> "$SILAGE_KWIN_ROOT/calls"
[ "$1" != --list-keys ] || [ -f "$SILAGE_KWIN_ROOT/key-trusted" ]
EOF
  cat > "$t/bin/curl" <<'EOF'
#!/bin/sh
echo "curl $*" >> "$SILAGE_KWIN_ROOT/calls"
d=$(mktemp -d); mkdir -p "$d/db"
for p in $(cat "$SILAGE_KWIN_ROOT/published" 2>/dev/null); do mkdir -p "$d/db/kwin-$p"; touch "$d/db/kwin-$p/desc"; done
(cd "$d/db" && tar -czf - *) 2>/dev/null || tar -czf - -T /dev/null
EOF
  cat > "$t/bin/rpm" <<'EOF'
#!/bin/sh
v=$(cat "$SILAGE_KWIN_ROOT/kwin-version")
case "$*" in
  *"%{VERSION}-%{RELEASE}"*) [ -n "$v" ] && echo "$v" || echo "package kwin is not installed" ;;
  *"%{NAME} %{RELEASE}"*) for n in kwin kwin-wayland kwin-libs; do echo "$n ${v#*-}"; done ;;
  *"%{NAME}"*) printf 'kwin\nkwin-wayland\nkwin-libs\n' ;;
esac
EOF
  cat > "$t/bin/dnf" <<'EOF'
#!/bin/sh
echo "dnf $*" >> "$SILAGE_KWIN_ROOT/calls"
case "$*" in
  *" upgrade "*) if [ -f "$SILAGE_KWIN_ROOT/published" ]; then cat "$SILAGE_KWIN_ROOT/published" > "$SILAGE_KWIN_ROOT/kwin-version"; fi ;;
  *distro-sync*) echo 6.7.5-1.fc43 > "$SILAGE_KWIN_ROOT/kwin-version" ;;
esac
exit 0
EOF
  chmod +x "$t/bin/"*
  PATH="$t/bin:$base_path"
  : > "$t/calls"
}

run() { set +e; out=$("$helper" "$@" 2>&1); code=$?; set -e; }
ok() {
  if eval "$1"; then echo "ok   $2"
  else echo "FAIL $2"; echo "     exit $code, output: $out"; echo "     calls: $(tr '\n' ';' < "$t/calls")"; fails=$((fails + 1)); fi
}

# Status on CachyOS with the patched KWin installed by hand and the monitor connected.
setup arch 6.8.2-2.99
run status
ok '[ "$out" = "$(printf "distro=arch\nrepo=no\npatched=yes\nmonitor=yes")" ]' "status reports distro, repository, patched KWin and monitor"

# Enable on Arch: repository first, key trusted, exactly the published package installed.
setup arch 6.8.2-2.1; echo 6.8.2-2.99 > "$t/published"
run enable
ok '[ $code = 0 ]' "enable succeeds"
ok '[ "$(grep -n "^\[" "$t/etc/pacman.conf" | head -2 | tr "\n" " ")" = "1:[options] 4:[silage-kwin] " ]' "[silage-kwin] goes before every other repository"
ok 'grep -qx "Server = https://github.com/evman/silage/releases/download/kwin-arch" "$t/etc/pacman.conf"' "the repository points at the kwin-arch release"
ok 'grep -q "pacman-key --add $t/key.asc" "$t/calls"' "Silage's key is trusted when missing"
ok 'grep -qx "pacman -U --noconfirm https://github.com/evman/silage/releases/download/kwin-arch/kwin-6.8.2-2.99-x86_64.pkg.tar.zst" "$t/calls"' "installs exactly the published package"
ok '! grep -q "^pacman -S" "$t/calls"' "never refreshes or partly upgrades the system"
ok '[ "$(echo "$out" | tail -1)" = "Dolby Vision KWin installed. Log out and back in to start using it." ]' "says to log out and in"
run enable
ok '[ "$(grep -c "^\[silage-kwin\]" "$t/etc/pacman.conf")" = 1 ]' "enabling twice adds the repository once"

# Enable when the installed KWin is a different version: update first, nothing installed.
setup arch 6.8.1-1; echo 6.8.2-2.99 > "$t/published"
run enable
ok '[ $code = 5 ] && ! grep -q "pacman -U" "$t/calls"' "a different KWin version asks for a system update first"

# Enable while there's no build for the current KWin: the repository stays, nothing installed.
setup arch 6.8.3-1
run enable
ok '[ $code = 4 ] && grep -q "^\[silage-kwin\]" "$t/etc/pacman.conf" && ! grep -q "pacman -U" "$t/calls"' "no build yet keeps the repository for later"

# Disable on Arch: repository removed, the distribution's kwin reinstalled.
setup arch 6.8.2-2.1; echo 6.8.2-2.99 > "$t/published"; run enable
run disable
ok '[ $code = 0 ] && ! grep -q "silage-kwin" "$t/etc/pacman.conf"' "disable removes the repository"
ok 'grep -qx "pacman -S --noconfirm kwin" "$t/calls" && [ "$(cat "$t/kwin-version")" = 6.8.2-2.1 ]' "disable reinstalls the distribution's KWin"
ok '[ "$(grep -n "^\[" "$t/etc/pacman.conf" | tr "\n" " ")" = "1:[options] 4:[core] 7:[extra] " ]' "pacman.conf is left as it was"

# Fedora: repository file, kwin packages upgraded to ours; disable syncs back to Fedora's.
setup fedora 6.7.5-1.fc43; echo 6.7.5-1.fc43.99silage > "$t/published"
run enable
ok '[ $code = 0 ] && grep -qx "baseurl=https://evman.github.io/silage/kwin-rpm/" "$t/etc/yum.repos.d/silage-kwin.repo"' "Fedora: adds the silage-kwin dnf repository"
ok 'grep -qx "dnf -y --refresh upgrade kwin kwin-libs kwin-wayland" "$t/calls"' "Fedora: upgrades the installed kwin packages"
run status
ok 'echo "$out" | grep -qx "patched=yes" && echo "$out" | grep -qx "repo=yes"' "Fedora: status sees the patched KWin"
run disable
ok '[ $code = 0 ] && [ ! -f "$t/etc/yum.repos.d/silage-kwin.repo" ] && grep -qx "dnf -y --refresh distro-sync kwin kwin-libs kwin-wayland" "$t/calls"' "Fedora: disable removes the repository and syncs back"

setup fedora 6.7.6-1.fc43
run enable
ok '[ $code = 4 ]' "Fedora: no build for a newer KWin yet"

# Not offered: other distributions, no verified monitor, no KWin.
setup other 6.8.2-1
run check
ok '[ $code = 3 ]' "other distributions aren't offered"
setup arch 6.8.2-2 nomonitor
run enable
ok '[ $code = 3 ] && ! grep -q "pacman -U" "$t/calls" && [ "$(grep -c silage-kwin "$t/etc/pacman.conf")" = 0 ]' "no verified monitor: nothing changes"
setup arch ""
run check
ok '[ $code = 3 ]' "no KWin installed: not offered"

if [ $fails = 0 ]; then echo "All silage-kwin-dv tests passed"; else echo "$fails failed"; exit 1; fi
