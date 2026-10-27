#!/usr/bin/env bash
# Generate Silage release signing keys into OUT_DIR (keep it outside the repo).
#
#   scripts/release-keys.sh OUT_DIR
#
# Produces:
#   repo-gpg-public.asc / repo-gpg-public.gpg   GPG public key (apt/rpm repositories)
#   repo-gpg-private.asc                        GPG private key, armored  (secret)
#   repo-gpg-fingerprint.txt                    GPG long fingerprint
#   update.key                                  Ed25519 private key, PKCS#8 PEM (secret)
#   update-key.pub                              Ed25519 public key, SPKI PEM
#
# Refuses to overwrite existing files. Private keys are never printed.
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: $0 OUT_DIR" >&2
  exit 2
fi
out=$1
files=(repo-gpg-public.asc repo-gpg-public.gpg repo-gpg-private.asc
  repo-gpg-fingerprint.txt update.key update-key.pub)

umask 077
mkdir -p "$out"
chmod 700 "$out"
for f in "${files[@]}"; do
  if [ -e "$out/$f" ]; then
    echo "refusing to overwrite existing file: $out/$f" >&2
    exit 1
  fi
done

gnupghome=$(mktemp -d)
trap 'rm -rf "$gnupghome"' EXIT
export GNUPGHOME=$gnupghome

uid="Silage Releases <evman311@gmail.com>"
gpg --batch --quiet --passphrase '' --quick-gen-key "$uid" rsa4096 sign never
fpr=$(gpg --batch --with-colons --list-keys "$uid" | awk -F: '/^fpr:/ {print $10; exit}')
if [ -z "$fpr" ]; then
  echo "could not determine GPG fingerprint" >&2
  exit 1
fi

gpg --batch --armor --export "$fpr" >"$out/repo-gpg-public.asc"
gpg --batch --export "$fpr" >"$out/repo-gpg-public.gpg"
gpg --batch --pinentry-mode loopback --passphrase '' --armor --export-secret-keys "$fpr" \
  >"$out/repo-gpg-private.asc"
printf '%s\n' "$fpr" >"$out/repo-gpg-fingerprint.txt"

openssl genpkey -algorithm ed25519 -out "$out/update.key"
openssl pkey -in "$out/update.key" -pubout -out "$out/update-key.pub"

chmod 600 "$out"/*
echo "Keys written to $out (GPG fingerprint $fpr)."
cat <<NEXT
Next steps:
  gh secret set REPO_GPG_PRIVATE_KEY --repo evman/silage < "$out/repo-gpg-private.asc"
  gh secret set REPO_GPG_KEY_ID      --repo evman/silage < "$out/repo-gpg-fingerprint.txt"
  gh secret set UPDATE_SIGNING_KEY   --repo evman/silage < "$out/update.key"
  cp "$out/repo-gpg-public.asc" packaging/keys/silage-archive-keyring.asc
  cp "$out/repo-gpg-public.gpg" packaging/keys/silage-archive-keyring.gpg
  cp "$out/update-key.pub" app/src/main/resources/update-key.pub
Then back up $out offline.
NEXT
