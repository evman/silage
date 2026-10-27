# Releasing Silage

## Who can release

Release tags (`v*`) are protected by the repository ruleset "Protect release
tags" (creation, update and deletion blocked). The bypass list is the
repository roles **Admin** and **Write**. User-owned repositories cannot list
individual users as bypass actors, so this is by role.

**Granting someone write access grants them release (tagging) rights** until
the ruleset is edited (Settings, Rules, Rulesets). Today that is `evman`
(admin) and `minimalhandle` (write) only.

## Cutting a release

1. On `main`, update the `CHANGELOG.md` section, the metainfo release entry,
   `pkgver` in `packaging/aur/PKGBUILD` and `.SRCINFO`, and the `0.X.Y-dev`
   default in `app/build.gradle.kts`, and commit. The release build takes its
   version from the tag.
2. Tag the merged commit and push the tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.
   Push the tag only after the commit is on `main`, so the release and the
   repositories describe the code users get.
3. The `release` workflow builds Windows and Linux, signs `SHA256SUMS` and the
   repositories, and checks the signature against the bundled `update-key.pub`
   (a mismatched key fails here, before anything is public). It publishes the
   release as not-latest, runs the install/upgrade tests on Ubuntu, Debian,
   Fedora, Arch and Windows against it, and only then the `promote` job marks it
   **Latest** and deploys the dnf repository to Pages.
4. A tag containing `-` (for example `v0.6.0-rc.1`) is a rehearsal: everything
   runs except `promote`, so users never see it. Delete the release and tag afterwards.

If a test job fails, nothing reaches users: fix on `main`, delete the release
and tag, and tag again.

### Pages deployment policy

The `github-pages` environment only accepts deployments from tags matching
`v*` (Settings, Environments, github-pages). Without that rule the `promote`
job cannot deploy the dnf repository from a tag.

### After promote: smoke check

```sh
base=https://github.com/evman/silage/releases/latest/download
for f in InRelease silage.db Silage-Setup.exe SHA256SUMS SHA256SUMS.sig; do
  curl -fsIL -o /dev/null -w "%{http_code} $f\n" "$base/$f"
done
curl -fsSL https://evman.github.io/silage/rpm/silage.repo
```

Every line should print `200`. Then check Settings, About, Check now on an
installed copy of the previous version.

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
