#!/usr/bin/env bash
# scripts/package.sh VERSION [NFPM]: builds the app image and the release
# artifacts into dist/: a portable tarball, a .deb, an .rpm and SHA256SUMS.
set -euo pipefail
version=${1:?usage: scripts/package.sh VERSION [path-to-nfpm]}
nfpm=${2:-nfpm}
cd "$(dirname "$0")/.."

./gradlew --no-daemon -q :app:createDistributable -PappVersion="$version"
app=app/build/compose/binaries/main/app/silage
test -x "$app/bin/silage"

rm -rf dist && mkdir -p dist
stage="dist/silage-$version"
mkdir -p "$stage"
cp -a "$app" "$stage/silage"
cp -a packaging/dev.silage.Silage.desktop packaging/dev.silage.Silage.metainfo.xml packaging/install.sh LICENSE README.md "$stage/"
cp -a packaging/icons "$stage/icons"
tar -C dist -czf "dist/silage-$version-linux-x86_64.tar.gz" "silage-$version"
rm -rf "$stage"

cp -a "$app" dist/app
for format in deb rpm; do
  SILAGE_VERSION=$version "$nfpm" pkg -f packaging/nfpm.yaml -p "$format" -t dist/
done
rm -rf dist/app
(cd dist && sha256sum ./*.tar.gz ./*.deb ./*.rpm | sed 's| \./| |' > SHA256SUMS)
ls -l dist
