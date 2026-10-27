#!/usr/bin/env bash
# scripts/tests/test-sign-release.sh: sign-release.sh lists every asset in SHA256SUMS and signs it
# with Ed25519 so that `openssl pkeyutl -verify -rawin` accepts it, and a changed byte does not.
# Uses a throwaway key generated here; never the real update key.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sign="$here/../sign-release.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

openssl genpkey -algorithm ed25519 -out "$tmp/update.key" 2>/dev/null
openssl pkey -in "$tmp/update.key" -pubout -out "$tmp/update.pub"

dist="$tmp/dist"
mkdir "$dist"
printf 'deb bytes' > "$dist/silage_0.5.0_amd64.deb"
printf 'setup bytes' > "$dist/Silage-0.5.0-Setup.exe"
printf 'stale' > "$dist/SHA256SUMS"

# A missing key is an error, not an unsigned release.
if (unset UPDATE_SIGNING_KEY; "$sign" "$dist") 2>/dev/null; then fail "signed without UPDATE_SIGNING_KEY"; fi

UPDATE_SIGNING_KEY=$(<"$tmp/update.key") "$sign" "$dist"

# Every asset is listed (but not the checksum files themselves), in sha256sum's format.
expected=$(cd "$dist" && sha256sum Silage-0.5.0-Setup.exe silage_0.5.0_amd64.deb)
[[ "$(<"$dist/SHA256SUMS")" == "$expected" ]] || fail "unexpected SHA256SUMS: $(<"$dist/SHA256SUMS")"
(cd "$dist" && sha256sum --quiet -c SHA256SUMS) || fail "SHA256SUMS does not match the assets"

# The signature is base64 of the raw 64-byte Ed25519 signature, on a single line.
[[ $(wc -l < "$dist/SHA256SUMS.sig") -eq 0 ]] || fail "SHA256SUMS.sig is not a single line"
base64 -d "$dist/SHA256SUMS.sig" > "$tmp/sig.bin" || fail "SHA256SUMS.sig is not base64"
[[ $(stat -c %s "$tmp/sig.bin") -eq 64 ]] || fail "signature is not 64 bytes"

verify() { openssl pkeyutl -verify -pubin -inkey "$tmp/update.pub" -rawin -in "$1" -sigfile "$tmp/sig.bin" >/dev/null 2>&1; }
verify "$dist/SHA256SUMS" || fail "openssl rejects the signature"

# One changed byte must break it.
cp "$dist/SHA256SUMS" "$tmp/tampered"
printf '%s' "$(head -c1 "$tmp/tampered" | tr '0-9a-f' '1-9a-f0')" | dd of="$tmp/tampered" bs=1 count=1 conv=notrunc status=none
cmp -s "$dist/SHA256SUMS" "$tmp/tampered" && fail "tampering did not change the file"
if verify "$tmp/tampered"; then fail "openssl accepts a tampered SHA256SUMS"; fi

# Re-signing replaces the old checksums instead of listing them.
UPDATE_SIGNING_KEY=$(<"$tmp/update.key") "$sign" "$dist"
grep -q 'SHA256SUMS' "$dist/SHA256SUMS" && fail "SHA256SUMS lists itself"

echo "test-sign-release: OK"
