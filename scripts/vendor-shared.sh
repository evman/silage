#!/usr/bin/env bash
# Regenerates every vendored file under shared/ from a silo-android checkout.
# Usage: scripts/vendor-shared.sh ~/src/silo-android
set -euo pipefail
UP="${1:?path to silo-android checkout}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S="$ROOT/shared/src"
UPS="$UP/shared/src"
PKG=kotlin/org/siloserver/silo

mkdir -p "$S"
rm -rf "$S/commonMain" "$S/commonTest"
cp -r "$UPS/commonMain" "$S/commonMain"
cp -r "$UPS/commonTest" "$S/commonTest"

# Silage's own additions inside commonMain/commonTest (letter seek, devices, history
# removal, advisory age, profile patching, runtime rules). Regenerate this patch after
# changing them: diff -ruN <upstream shared/src> <ours shared/src>, paths a/shared/src/...
if ! git -C "$ROOT" apply --whitespace=nowarn shared/silage-local.patch; then
  echo "ERROR: shared/silage-local.patch no longer applies; merge it by hand (git apply --reject)" >&2; exit 1
fi

# Pure-JVM androidMain files, copied verbatim under desktop names.
D="$S/desktopMain/$PKG"
mkdir -p "$D/model/settings" "$D/player" "$D/network/vendored"
cp "$UPS/androidMain/$PKG/model/settings/LanguagePresentation.android.kt" "$D/model/settings/LanguagePresentation.desktop.kt"
cp "$UPS/androidMain/$PKG/player/SubtitleTrackDisplayLabel.kt"            "$D/player/SubtitleTrackDisplayLabel.kt"

# English string catalog (generated Android resource) for the desktop UiText resolver,
# without its per-string translator comments.
mkdir -p "$S/desktopMain/resources/org/siloserver/silo/i18n"
grep -vE '^\s*<!--.*-->\s*$' "$UP/android-shared/src/androidMain/res/values/strings_catalog.xml" \
  > "$S/desktopMain/resources/org/siloserver/silo/i18n/strings_catalog.xml"

# Persistence classes: swap the two Android imports for desktop shims (Task 2).
for f in EncryptedTokenManagerImpl.kt AndroidServerRegistry.kt; do
  sed -e 's/^import android\.content\.SharedPreferences$/import org.siloserver.silo.platform.SharedPreferences/' \
      -e 's/^import android\.util\.Base64$/import org.siloserver.silo.platform.Base64/' \
      -e 's/\bandroid\.util\.Log\./org.siloserver.silo.platform.Log./g' \
      "$UPS/androidMain/$PKG/network/$f" > "$D/network/vendored/$f"
  if grep -vE '^\s*(\*|//)' "$D/network/vendored/$f" | grep -qE '^import android|[^.a-z]android\.(util|content|os)\.'; then
    echo "ERROR: $f still has Android imports" >&2; exit 1
  fi
done

# Brand-asset guard: Silo logos must never enter this repo.
if find "$S/commonMain" "$S/commonTest" -iname '*logo*' -o -iname '*wordmark*' | grep -q .; then
  echo "ERROR: brand asset found in vendored tree" >&2; exit 1
fi

git -C "$UP" rev-parse HEAD > "$ROOT/shared/UPSTREAM_COMMIT"
echo "Vendored silo-android $(cat "$ROOT/shared/UPSTREAM_COMMIT")"
