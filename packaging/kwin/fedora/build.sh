#!/usr/bin/env bash
# Rebuilds Fedora's own kwin source package with Silage's Dolby Vision patch, the
# release raised to <Fedora's>.99silage so dnf prefers it over the same version;
# a newer Fedora kwin is preferred again until this is rebuilt (systems update
# normally and fall back). Every kwin subpackage is rebuilt at that release.
#
# Run as root in a Fedora container: packaging/kwin/fedora/build.sh <out dir>
# Prints the built version; leaves the RPMs in <out dir>.
set -euo pipefail
out=$(realpath -m "${1:?out dir}")
here=$(cd "$(dirname "$0")" && pwd)
dnf -y -q install rpm-build dnf-plugins-core rpmdevtools >/dev/null
work=$(mktemp -d)
dnf -q download --source kwin --destdir "$work" >/dev/null
srpm=$(ls "$work"/kwin-*.src.rpm)
rpm -i --define "_topdir $work/rpmbuild" "$srpm" 2>/dev/null
spec="$work/rpmbuild/SPECS/kwin.spec"
version=$(rpmspec -q --srpm --qf '%{VERSION}' "$spec")
minor=$(echo "$version" | cut -d. -f1-2)
patch="$here/../silage-dv-$minor.patch"
[ -f "$patch" ] || { echo "No Silage patch for KWin $minor (packaging/kwin/silage-dv-$minor.patch)" >&2; exit 3; }
cp "$patch" "$work/rpmbuild/SOURCES/silage-dv.patch"

# %autosetup -p1 applies every PatchN; add ours and raise the release.
grep -q '^%autosetup -p1' "$spec" || { echo "Fedora's kwin spec no longer uses %autosetup -p1" >&2; exit 4; }
sed -i -E \
    -e 's/^(Release:[[:space:]]*)([^[:space:]]+)%\{\?dist\}$/\1\2%{?dist}.99silage/' \
    -e '0,/^Source1:/s//Patch9001: silage-dv.patch\nSource1:/' \
    "$spec"
grep -q '^Patch9001: silage-dv.patch' "$spec" && grep -q '%{?dist}.99silage' "$spec" \
    || { echo "Couldn't adapt Fedora's kwin spec" >&2; exit 5; }

dnf -y -q builddep "$spec" >/dev/null
rpmbuild --define "_topdir $work/rpmbuild" --nocheck -bb "$spec" >"$work/build.log" 2>&1 \
    || { tail -40 "$work/build.log" >&2; exit 6; }
mkdir -p "$out"
find "$work/rpmbuild/RPMS" -name '*.rpm' ! -name '*-debuginfo-*' ! -name '*-debugsource-*' -exec cp {} "$out/" \;
rpm -qp --qf '%{VERSION}-%{RELEASE}\n' "$out"/kwin-[0-9]*.rpm | head -1
