# Vendored silo-android `shared`

- Source: https://github.com/Silo-Server/silo-android, `shared/`
- Commit: see `UPSTREAM_COMMIT` (initially 538aa09d00f3236066193e828f8435eb23e31713)
- License: AGPL-3.0-or-later. Silo name and brand assets are not vendored.
- Regenerate: `scripts/vendor-shared.sh <silo-android checkout>`. Don't edit `commonMain/` or `commonTest/` by hand
  without also regenerating `silage-local.patch` (see below), or the next re-vendor drops the change.

## Silage changes inside commonMain/commonTest

`silage-local.patch` holds them; the vendor script applies it after copying upstream. It adds the
library letter index and seek, login sessions (Settings → Devices), watch-history removal, the
advisory age, profile patching, numeric runtime filter rules and a few related tests.
To change one: edit the file, then regenerate the patch as a `diff -ruN` of upstream's
`shared/src/{commonMain,commonTest}` against ours, with paths `a/shared/src/...` and `b/shared/src/...`.

## Local changes outside commonMain/commonTest

| File | Why |
|---|---|
| desktopMain/util/Dispatchers.desktop.kt | actual dispatchers (main = Default) |
| desktopMain/util/WallClock.desktop.kt | actual clock |
| desktopMain/network/SiloHttpClient.desktop.kt | OkHttp engine without Android tuning |
| desktopMain/model/settings/LanguagePresentation.desktop.kt | verbatim copy of androidMain (pure JVM) |
| desktopMain/player/SubtitleTrackDisplayLabel.kt | verbatim copy of androidMain (pure JVM) |
| desktopMain/common/i18n/DesktopText.kt | `UiText.text()`: resolves shared text from the English catalog |
| desktopMain/resources/org/siloserver/silo/i18n/strings_catalog.xml | android-shared's generated English `values/strings_catalog.xml`, comments stripped (copied by the script) |
| desktopTest/**/…Fixture….desktop.kt | test fixture loaders (verbatim copies of androidUnitTest) |
| desktopTest/common/i18n/EnglishText.desktop.kt | test `actual` for `UiText.english()`, via `text()` |
| desktopMain/platform/SharedPreferences.kt, Base64.kt, Log.kt | shims so upstream persistence classes compile with an import swap |
| desktopMain/platform/PersistentPreferences.kt, SecureBlobStore.kt | JSON blob in Secret Service keyring; 0600 file fallback |
| desktopMain/platform/RememberedSessionStore.kt, LinuxSessionStore.kt | Remember me: credentials (`<server>.<key>` and `<server>.<account>.<key>`) persist only with consent |
| desktopMain/network/vendored/{EncryptedTokenManagerImpl,AndroidServerRegistry}.kt | androidMain copies; script swaps `android.content.SharedPreferences` and `android.util.Base64` imports and rewrites `android.util.Log.` calls |

Desktop test baseline at 48015d4: 2340 shared tests, 0 failures.
