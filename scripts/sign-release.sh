#!/usr/bin/env bash
# scripts/sign-release.sh DIST_DIR: writes SHA256SUMS (every release asset in DIST_DIR) and
# SHA256SUMS.sig, the raw Ed25519 signature over SHA256SUMS's exact bytes, base64 on one line.
# The private key (PKCS#8 PEM) comes from $UPDATE_SIGNING_KEY; the app verifies with update-key.pub.
set -euo pipefail
dist=${1:?usage: scripts/sign-release.sh DIST_DIR}
: "${UPDATE_SIGNING_KEY:?UPDATE_SIGNING_KEY (Ed25519 private key, PEM) is not set}"
cd "$dist"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
key="$tmp/update.key"
(umask 077 && printf '%s\n' "$UPDATE_SIGNING_KEY" > "$key")

rm -f SHA256SUMS SHA256SUMS.sig
mapfile -t assets < <(find . -maxdepth 1 -type f ! -name '.*' -printf '%f\n' | LC_ALL=C sort)
(( ${#assets[@]} )) || { echo "No release assets in $dist" >&2; exit 1; }
sha256sum -- "${assets[@]}" > "$tmp/SHA256SUMS"
openssl pkeyutl -sign -rawin -inkey "$key" -in "$tmp/SHA256SUMS" -out "$tmp/sig.bin"
[[ $(stat -c %s "$tmp/sig.bin") -eq 64 ]] || { echo "UPDATE_SIGNING_KEY is not an Ed25519 key" >&2; exit 1; }
base64 -w0 "$tmp/sig.bin" > "$tmp/SHA256SUMS.sig"
chmod 644 "$tmp/SHA256SUMS" "$tmp/SHA256SUMS.sig"
mv "$tmp/SHA256SUMS" "$tmp/SHA256SUMS.sig" .
