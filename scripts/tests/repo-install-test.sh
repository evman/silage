#!/usr/bin/env bash
# scripts/tests/repo-install-test.sh IMAGE BASE_URL PAGES_URL
# Installs Silage in a fresh IMAGE container (Debian/Ubuntu, Fedora or Arch) through its signed
# repository, configured by hand: apt and pacman from BASE_URL (a release download URL), dnf from
# PAGES_URL (the Pages rpm/ tree; its silage.repo is used with the URLs pointed here). Then checks
# `silage --version` prints "Silage $SILAGE_EXPECTED_VERSION" (default: the v<version> at the end
# of BASE_URL) and `silage --check` passes.
#   SILAGE_UPGRADE_FROM=<tag>  first install that release's package directly from GitHub, then
#                              upgrade it with the package manager through the repository.
#   SILAGE_REPO_KEYS=<dir>     silage-archive-keyring.{gpg,asc} to trust (default packaging/keys).
#   CONTAINER_ENGINE=<cmd>     docker or podman (default: whichever is installed).
# The container shares the host network, so either URL may be a server on localhost.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
usage="usage: scripts/tests/repo-install-test.sh IMAGE BASE_URL PAGES_URL"
image=${1:?$usage}
base=${2:?$usage}
pages=${3:?$usage}
base=${base%/}
pages=${pages%/}
fail() { echo "FAIL: $*" >&2; exit 1; }

if [[ -z "${SILAGE_REPO_TEST_IN_CONTAINER:-}" ]]; then
  expected=${SILAGE_EXPECTED_VERSION:-}
  if [[ -z "$expected" ]]; then
    tag=${base##*/}
    [[ "$tag" == v* ]] || fail "set SILAGE_EXPECTED_VERSION (BASE_URL does not end in a v<version> tag)"
    expected=${tag#v}
  fi
  keys=$(realpath "${SILAGE_REPO_KEYS:-$here/../../packaging/keys}")
  engine=${CONTAINER_ENGINE:-$(command -v docker || command -v podman || true)}
  [[ -n "$engine" ]] || fail "docker or podman is needed"
  exec "$engine" run --rm --network host \
    -v "$here/repo-install-test.sh:/repo-install-test.sh:ro" -v "$keys:/keys:ro" \
    -e SILAGE_REPO_TEST_IN_CONTAINER=1 -e SILAGE_EXPECTED_VERSION="$expected" \
    -e SILAGE_UPGRADE_FROM="${SILAGE_UPGRADE_FROM:-}" \
    "$image" bash /repo-install-test.sh "$image" "$base" "$pages"
fi

# Inside the container from here on.
expected=$SILAGE_EXPECTED_VERSION
from=${SILAGE_UPGRADE_FROM:-}
from_version=${from#v}
from_url="https://github.com/evman/silage/releases/download/$from"
echo "== $image: Silage $expected from $base${from:+ (upgrading from $from)}"

assert_version() {
  local reported
  reported=$(silage --version)
  [[ "$reported" == "Silage $1" ]] || fail "expected Silage $1, got: $reported"
}

if command -v apt-get >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -q
  apt-get install -yq ca-certificates curl
  if [[ -n "$from" ]]; then
    curl -fsSLo /tmp/previous.deb "$from_url/silage_${from_version}_amd64.deb"
    apt-get install -yq /tmp/previous.deb
    assert_version "$from_version"
  fi
  install -Dm644 /keys/silage-archive-keyring.gpg /usr/share/keyrings/silage-archive-keyring.gpg
  echo "deb [signed-by=/usr/share/keyrings/silage-archive-keyring.gpg] $base ./" > /etc/apt/sources.list.d/silage.list
  apt-get update -q --error-on=any
  if [[ -n "$from" ]]; then apt-get upgrade -yq; else apt-get install -yq silage; fi
elif command -v dnf >/dev/null; then
  if [[ -n "$from" ]]; then
    # Releases before 0.5.0 aren't signed.
    dnf install -y --nogpgcheck "$from_url/silage-${from_version}-1.x86_64.rpm"
    assert_version "$from_version"
  fi
  curl -fsSL "$pages/silage.repo" \
    | sed -e 's|^gpgkey=.*|gpgkey=file:///keys/silage-archive-keyring.asc|' \
          -e "s|^baseurl=.*|baseurl=$pages/|" > /etc/yum.repos.d/silage.repo
  for setting in gpgcheck=1 repo_gpgcheck=1; do
    grep -qx "$setting" /etc/yum.repos.d/silage.repo || fail "silage.repo lacks $setting"
  done
  if [[ -n "$from" ]]; then dnf upgrade -y; else dnf install -y silage; fi
  rpm -qi silage | grep -E '^Signature +:' | grep -qv '(none)' || fail "the installed rpm is not signed"
elif command -v pacman >/dev/null; then
  pacman -Syu --noconfirm
  if [[ -n "$from" ]]; then
    curl -fsSLo /tmp/previous.pkg.tar.zst "$from_url/silage-bin-${from_version//-/_}-1-x86_64.pkg.tar.zst"
    pacman -U --noconfirm /tmp/previous.pkg.tar.zst
    assert_version "$from_version"
  fi
  pacman-key --init
  pacman-key --add /keys/silage-archive-keyring.asc
  fpr=$(gpg --show-keys --with-colons /keys/silage-archive-keyring.asc | awk -F: '/^fpr/ {print $10; exit}')
  pacman-key --lsign-key "$fpr"
  printf '\n[silage]\nSigLevel = Required DatabaseRequired\nServer = %s\n' "$base" >> /etc/pacman.conf
  # Arch only supports full upgrades (never -Sy alone).
  if [[ -n "$from" ]]; then pacman -Syu --noconfirm; else pacman -Syu --noconfirm silage-bin; fi
else
  fail "$image has no apt-get, dnf or pacman"
fi

assert_version "$expected"
silage --check
/usr/libexec/silage-dv-helper/silage-dv-helper --version
[[ "$(stat -c '%u:%a' /usr/libexec/silage-dv-helper/silage-dv-helper)" == '0:755' ]] || fail "helper is not root-owned 0755"
echo "== $image: OK"
