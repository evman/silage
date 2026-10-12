# Vendored silo-android `shared`

- Source: https://github.com/Silo-Server/silo-android, `shared/`
- Commit: see `UPSTREAM_COMMIT` (initially 538aa09d00f3236066193e828f8435eb23e31713)
- License: AGPL-3.0-or-later. Silo name and brand assets are not vendored.
- Regenerate: `scripts/vendor-shared.sh <silo-android checkout>`. Never edit `commonMain/` or `commonTest/` by hand.

## Local changes (all outside commonMain/commonTest)

| File | Why |
|---|---|
| desktopMain/util/Dispatchers.desktop.kt | actual dispatchers (main = Default) |
| desktopMain/util/WallClock.desktop.kt | actual clock |
| desktopMain/network/SiloHttpClient.desktop.kt | OkHttp engine without Android tuning |
| desktopMain/model/settings/LanguagePresentation.desktop.kt | verbatim copy of androidMain (pure JVM) |
| desktopMain/overlays/OverlayLanguageName.desktop.kt | verbatim copy of androidMain (pure JVM) |
| desktopMain/player/SubtitleTrackDisplayLabel.kt | verbatim copy of androidMain (pure JVM) |
| desktopTest/**/…Fixture….desktop.kt | test fixture loaders (verbatim copies of androidUnitTest) |

Upstream test baseline on desktop: 1869 tests, 0 failures.
