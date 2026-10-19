#!/usr/bin/env bash
# scripts/package.sh VERSION [NFPM]: builds Linux release artifacts into dist/.
# Keep Windows builds and previous versions; stage this build until it succeeds.
set -euo pipefail
version=${1:?usage: scripts/package.sh VERSION [path-to-nfpm]}
nfpm=${2:-nfpm}
cd "$(dirname "$0")/.."
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]] || { echo "Invalid version: $version" >&2; exit 2; }

packaging_args=()
if [[ -n "${SILAGE_PACKAGING_JAVA_HOME:-}" ]]; then
  packaging_args+=("-PpackagingJavaHome=$SILAGE_PACKAGING_JAVA_HOME")
fi
./gradlew --no-daemon -q :app:createDistributable -PappVersion="$version" "${packaging_args[@]}"
app=$(realpath app/build/compose/binaries/main/app/silage)
test -x "$app/bin/silage"

mkdir -p dist
stage=$(mktemp -d dist/.linux-package.XXXXXX)
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/silage-$version"
cp -a "$app" "$stage/silage-$version/silage"
cp -a packaging/dev.silage.Silage.desktop packaging/dev.silage.Silage.metainfo.xml packaging/install.sh LICENSE README.md LINUX.md "$stage/silage-$version/"
cp -a packaging/icons "$stage/silage-$version/icons"
tar -C "$stage" -czf "$stage/silage-$version-linux-x86_64.tar.gz" "silage-$version"
rm -rf "$stage/silage-$version"

# nfpm does not expand environment variables in content source paths.
# Render just this path, preserving YAML quoting even if the checkout has spaces.
config=$(<packaging/nfpm.yaml)
yaml_app=${app//\'/\'\'}
config=${config/'src: dist/app/'/"src: '$yaml_app/'"}
printf '%s\n' "$config" > "$stage/nfpm.yaml"
for format in deb rpm; do
  SILAGE_VERSION=$version "$nfpm" pkg -f "$stage/nfpm.yaml" -p "$format" -t "$stage/"
done
mv "$stage/"*.tar.gz "$stage/"*.deb "$stage/"*.rpm dist/
(cd dist && shopt -s nullglob && sha256sum ./*.tar.gz ./*.deb ./*.rpm ./*.pkg.tar.zst ./*.exe ./*.zip | sed 's| \./| |' > SHA256SUMS)
ls -l dist
