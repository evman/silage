#!/bin/sh
# Installs Silage from this tarball: ./install.sh (into ~/.local) or sudo ./install.sh (into /usr/local).
# ./install.sh --uninstall removes it again.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
if [ "$(id -u)" = 0 ]; then prefix=${PREFIX:-/usr/local}; else prefix=${PREFIX:-$HOME/.local}; fi
app="$prefix/lib/silage"

if [ "${1:-}" = "--uninstall" ]; then
  rm -rf "$app" "$prefix/bin/silage" "$prefix/share/applications/dev.silage.Silage.desktop" "$prefix/share/metainfo/dev.silage.Silage.metainfo.xml"
  find "$prefix/share/icons/hicolor" -name dev.silage.Silage.png -delete 2>/dev/null || true
  echo "Silage removed from $prefix"
  exit 0
fi

mkdir -p "$app" "$prefix/bin" "$prefix/share/applications" "$prefix/share/metainfo"
rm -rf "$app" && cp -a "$here/silage" "$app"
ln -sf "$app/bin/silage" "$prefix/bin/silage"
sed "s|^Exec=silage|Exec=$app/bin/silage|" "$here/dev.silage.Silage.desktop" > "$prefix/share/applications/dev.silage.Silage.desktop"
cp "$here/dev.silage.Silage.metainfo.xml" "$prefix/share/metainfo/"
cp -a "$here/icons/." "$prefix/share/icons/"
command -v update-desktop-database >/dev/null && update-desktop-database "$prefix/share/applications" 2>/dev/null || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q "$prefix/share/icons/hicolor" 2>/dev/null || true
echo "Silage installed into $prefix (run: silage)"
"$app/bin/silage" --check || true
