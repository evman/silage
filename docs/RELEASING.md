# Releasing Silage

## Who can release

Release tags (`v*`) are protected by the repository ruleset "Protect release
tags" (creation, update and deletion blocked). The bypass list is the
repository roles **Admin** and **Write**. User-owned repositories cannot list
individual users as bypass actors, so this is by role.

**Granting someone write access grants them release (tagging) rights** until
the ruleset is edited (Settings, Rules, Rulesets). Today that is `evman`
(admin) and `minimalhandle` (write) only.

## Secrets

Repository secrets used by the release workflows:

| Secret | Contents |
| --- | --- |
| `REPO_GPG_PRIVATE_KEY` | Armored GPG private key that signs the apt/rpm repositories |
| `REPO_GPG_KEY_ID` | Long fingerprint of that key |
| `UPDATE_SIGNING_KEY` | Ed25519 private key (PKCS#8 PEM) that signs update manifests |

The matching public keys are committed:

- `packaging/keys/silage-archive-keyring.asc` and `.gpg` (GPG, for apt/rpm users)
- `app/src/main/resources/update-key.pub` (Ed25519 SPKI PEM, verified by the in-app updater)

GitHub Pages is enabled with source "GitHub Actions" and hosts the repositories.

## Generating keys

`scripts/release-keys.sh OUT_DIR` creates all keys in a directory outside the
repo (mode 700) and refuses to overwrite existing files. It prints the
`gh secret set` commands to run next.

## Backup

Back up the key directory (`~/silage-release-keys`) offline, for example on an
encrypted drive, and share it with the co-maintainer if desired. Then delete
the local copy. The private keys are not recoverable from GitHub, since
secrets are write-only.

## Rotation

- **GPG key:** run the script into a new directory, update the two secrets and
  the committed keyring files, and publish a release. Users must re-import the
  new keyring, so announce it in the release notes.
- **Update signing key:** the public key is compiled into the app, so
  installed clients only trust the old key. Ship a release signed with the old
  key that contains the new `update-key.pub` first; only after users have
  updated, switch `UPDATE_SIGNING_KEY` to the new private key.
- If a private key leaks, rotate immediately and treat previously signed
  artifacts as untrusted.
