# Silage Plan 2: Browsing UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder screens with a polished, Plex-HTPC-style browsing app: a left sidebar listing the user's libraries and Settings, a Home screen of poster rows, library poster grids, movie and series detail pages, search, and playback that resumes where you left off.

**Architecture:** One `AppShell` composable hosts a collapsible sidebar and the current page. Each page has a small, unit-tested presenter that turns `shared` repository results (`SectionRepository`, `CatalogRepository`, `PersonalDataRepository`, `CollectionRepository`) into card and row models. The pages are Compose UI drawn from a shared design-token set. Artwork loads through Coil 3 with a disk cache under `$XDG_CACHE_HOME/silage/images`, with a thumbhash blur placeholder decoded locally.

**Tech Stack:** Kotlin 2.1, Compose Multiplatform 1.7.3 (Desktop), Material 3, Coil 3.1 (`coil-compose`, `coil-network-ktor3`), Ktor 3.1, Koin 4.1, the vendored `shared` module.

**Spec:** `docs/superpowers/specs/2026-10-11-silage-design.md` (§5 Screens and navigation, §8 Settings).

## Global Constraints

- Branding: the app is **Silage**, "a Silo client for Linux". Never name anything "Silo…". No Silo logos; Silo colors and the three-bar motif are allowed.
- Layout is specified in 1920×1080 dp and scaled uniformly (density = window height / 1080), as the player HUD already does.
- Colors: background `#000000`, surface `#0A0A0A`, elevated `#15171C`, text `#EDEDED` (secondary 60 %, tertiary 38 %), hairlines white 12 %, active accent amber `#E8B86E` (Silo's player accent), error `#B00020`.
- Focus: the focused card scales to 1.06 and gets a 3 dp white ring. Focused buttons and pills invert to a white fill with a black label.
- Every page is fully usable with the keyboard alone (arrows, Enter, Esc/Backspace) and with the mouse (hover, click).
- Image requests carry no credentials. URLs from `shared` models are already absolute (resolved during decoding).
- Device-only UI preferences live in `$XDG_CONFIG_HOME/silage/ui.json`, not in the session store.
- Tests: `./gradlew :app:test` must stay green. Run shared tests with `./gradlew :shared:desktopTest`; Gradle needs `-Xmx4g`.

## Review Focus

1. **A library with zero items, or a library page whose next page fails to load:** the grid shows "Nothing here yet" or keeps its items with a retry row; it never spins forever. Tested in Task 6 (`LibraryPagerTest`).
2. **An item without a poster:** the card shows the thumbhash, or a titled dark tile when there's no thumbhash either; never a blank hole. Tested in Task 2 (`ArtworkTest`) and Task 4 (`CardModelTest`).
3. **The sidebar preferences file holds a library that no longer exists, or misses a new one:** stale entries are dropped and new libraries are appended visible. Tested in Task 3 (`SidebarEntriesTest`).
4. **Resume:** an in-progress item starts at its saved position, and "Play from beginning" starts at 0. A watched item (`played`) starts at 0. Tested in Task 8 (`PlayStartTest`).
5. **Back always goes somewhere sensible:** player → the page you came from (not Home); detail → the page you came from; sidebar root pages → Home; Home → stays. Tested in Task 1 (`AppNavigatorTest`).

---

### Task 1: Navigation with a back stack

Today `AppNavigator` holds one route and playback always returns to Home. Pages need a back stack.

**Files:**
- Modify: `app/src/main/kotlin/dev/silage/app/nav/AppNavigator.kt` (currently `Route` + `AppNavigator` in `nav/`)
- Test: `app/src/test/kotlin/dev/silage/app/nav/AppNavigatorTest.kt`

**Interfaces:**
- Produces: `sealed interface Route` with `Connect, SignIn, Profiles, Home, Search, Watchlist, Settings, Library(id: Int, name: String), Collection(id: String, name: String), Detail(contentId: String), Playing(contentId: String, fromStart: Boolean = false)`. `AppNavigator(start)`: `route: StateFlow<Route>`, `go(route)` (push), `root(route)` (clear the stack, used by the sidebar and the auth flow), `back(): Boolean` (false when there is nowhere to go).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.silage.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigatorTest {
    @Test fun playerReturnsToThePageItCameFrom() {
        val nav = AppNavigator(Route.Home)
        nav.root(Route.Library(1, "Movies"))
        nav.go(Route.Detail("m1"))
        nav.go(Route.Playing("m1"))
        assertTrue(nav.back())
        assertEquals(Route.Detail("m1"), nav.route.value)
        assertTrue(nav.back())
        assertEquals(Route.Library(1, "Movies"), nav.route.value)
    }

    @Test fun sidebarPagesBackOutToHomeAndHomeStays() {
        val nav = AppNavigator(Route.Home)
        nav.root(Route.Settings)
        assertTrue(nav.back())
        assertEquals(Route.Home, nav.route.value)
        assertFalse(nav.back())
        assertEquals(Route.Home, nav.route.value)
    }

    @Test fun authRoutesClearTheStack() {
        val nav = AppNavigator(Route.Home)
        nav.go(Route.Detail("x"))
        nav.root(Route.Profiles)
        assertFalse(nav.back())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:test --tests '*AppNavigatorTest*'`
Expected: FAIL to compile (`root`, `back`, `Route.Library` unresolved)

- [ ] **Step 3: Implement**

```kotlin
package dev.silage.app.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Route {
    data object Connect : Route
    data object SignIn : Route
    data object Profiles : Route
    data object Home : Route
    data object Search : Route
    data object Watchlist : Route
    data object Settings : Route
    data class Library(val id: Int, val name: String) : Route
    data class Collection(val id: String, val name: String) : Route
    data class Detail(val contentId: String) : Route
    data class Playing(val contentId: String, val fromStart: Boolean = false) : Route
}

private val authRoutes = setOf(Route.Connect, Route.SignIn, Route.Profiles)

class AppNavigator(start: Route) {
    private val stack = ArrayDeque(listOf(start))
    private val _route = MutableStateFlow(start)
    val route: StateFlow<Route> = _route.asStateFlow()

    fun go(route: Route) { stack.addLast(route); _route.value = route }

    /** A sidebar destination or an auth step: replaces the stack (sidebar pages keep Home beneath them). */
    fun root(route: Route) {
        stack.clear()
        if (route != Route.Home && route !in authRoutes) stack.addLast(Route.Home)
        stack.addLast(route)
        _route.value = route
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        _route.value = stack.last()
        return true
    }
}
```

- [ ] **Step 4: Run it to verify it passes**, then update callers in `Main.kt`: auth transitions use `nav.root(...)`, and `PlayingScreen(onDone = { nav.back() })`.

Run: `./gradlew :app:test`
Expected: PASS (all)

- [ ] **Step 5: Commit** `feat(app): navigation back stack`

---

### Task 2: Design tokens, theme, and artwork loading

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/ui/Tokens.kt` (colors, type scale, `Modifier.focusCard()`, `Modifier.invertOnFocus()`)
- Create: `app/src/main/kotlin/dev/silage/app/ui/Thumbhash.kt` (decoder ported from silo-android `android-shared/.../ThumbhashImage.kt`, returning an `ImageBitmap`)
- Create: `app/src/main/kotlin/dev/silage/app/ui/Artwork.kt` (`Artwork(url, thumbhash, title, modifier)` composable; the Coil `ImageLoader` singleton with a disk cache at `XdgPaths.cache/images`, 512 MB)
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (add `io.coil-kt.coil3:coil-compose:3.1.0` and `io.coil-kt.coil3:coil-network-ktor3:3.1.0`)
- Test: `app/src/test/kotlin/dev/silage/app/ui/ThumbhashTest.kt`, `app/src/test/kotlin/dev/silage/app/ui/ArtworkTest.kt`

**Interfaces:**
- Produces: `object Silage { val Black, Surface, Elevated, Text, Text2, Text3, Hairline, Amber }`; `Thumbhash.decode(hash: String): ThumbhashImage?` (`data class ThumbhashImage(width: Int, height: Int, rgba: ByteArray)`); `fun artworkFallback(url: String?, thumbhash: String?, title: String): ArtworkFallback` (`Image(url)`, `Blur(thumbhash)`, or `TitleTile(title)`); `@Composable fun Artwork(url: String?, thumbhash: String?, title: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// ThumbhashTest.kt — the reference hash from evanw/thumbhash's README.
class ThumbhashTest {
    @Test fun decodesTheReferenceHash() {
        val img = Thumbhash.decode("1QcSHQRnh493V4dIh4eXh1h4kJUI")!!
        assertEquals(img.width * img.height * 4, img.rgba.size)
        assertTrue(img.width in 20..32 && img.height in 20..32, "${img.width}x${img.height}")
    }
    @Test fun garbageIsNull() { assertNull(Thumbhash.decode("!!")) }
}

// ArtworkTest.kt
class ArtworkTest {
    @Test fun prefersTheImageThenTheBlurThenATitleTile() {
        assertEquals(ArtworkFallback.Image("http://s/p.jpg"), artworkFallback("http://s/p.jpg", "abc", "Dune"))
        assertEquals(ArtworkFallback.Blur("abc"), artworkFallback(null, "abc", "Dune"))
        assertEquals(ArtworkFallback.TitleTile("Dune"), artworkFallback("  ", null, "Dune"))
    }
}
```

- [ ] **Step 2: Run them to verify they fail** (unresolved references).

- [ ] **Step 3: Implement.** Port the thumbhash decoder line for line from the Android file (it is the reference algorithm: header bits → DCT coefficients → RGBA). `artworkFallback` returns `Image` when `url` is non-blank, otherwise `Blur` when the thumbhash is non-blank, otherwise `TitleTile`. `Artwork` draws the blur (or the title tile) underneath and Coil's `AsyncImage` on top, with a 180 ms crossfade. The `ImageLoader` is built once with `KtorNetworkFetcherFactory()` and `DiskCache.Builder().directory(XdgPaths.cache.resolve("images")).maxSizeBytes(512L shl 20)`.

- [ ] **Step 4: Run tests to verify they pass.** Run: `./gradlew :app:test`. Expected: PASS.

- [ ] **Step 5: Commit** `feat(app): design tokens and cached artwork with thumbhash placeholders`

---

### Task 3: Sidebar entries and their preferences

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/shell/SidebarEntries.kt`
- Create: `app/src/main/kotlin/dev/silage/app/ui/UiPrefs.kt` (JSON file at `XdgPaths.config/ui.json`)
- Test: `app/src/test/kotlin/dev/silage/app/shell/SidebarEntriesTest.kt`, `app/src/test/kotlin/dev/silage/app/ui/UiPrefsTest.kt`

**Interfaces:**
- Consumes: `UserLibrary(id: Int, name: String, type: String, sortOrder: Int, posterUrl: String?)` from `PersonalDataRepository.listUserLibraries()`.
- Produces: `data class SidebarLibraryPref(val id: Int, val visible: Boolean)`; `object SidebarEntries { fun libraries(server: List<UserLibrary>, prefs: List<SidebarLibraryPref>): List<SidebarLibrary> }` with `data class SidebarLibrary(val id: Int, val name: String, val kind: LibraryKind, val visible: Boolean)` and `enum class LibraryKind { Movies, Shows, Music, Other }`; `class UiPrefs(path: Path) { var sidebar: List<SidebarLibraryPref>; var hdrMode: String /* auto|on|off */ }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class SidebarEntriesTest {
    private val movies = UserLibrary(1, "Movies", "movie", 0, null)
    private val shows = UserLibrary(2, "TV Shows", "series", 1, null)
    private val music = UserLibrary(3, "Music", "music", 2, null)

    @Test fun serverOrderWhenThereAreNoPrefs() {
        val e = SidebarEntries.libraries(listOf(shows, movies, music).shuffled(), emptyList())
        assertEquals(listOf(1, 2, 3), e.map { it.id })
        assertEquals(listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music), e.map { it.kind })
        assertTrue(e.all { it.visible })
    }

    @Test fun prefsReorderAndHide() {
        val e = SidebarEntries.libraries(listOf(movies, shows, music), listOf(SidebarLibraryPref(2, true), SidebarLibraryPref(1, false)))
        assertEquals(listOf(2, 1, 3), e.map { it.id })
        assertEquals(listOf(true, false, true), e.map { it.visible })
    }

    @Test fun staleLibrariesAreDroppedAndNewOnesAppendedVisible() {
        val e = SidebarEntries.libraries(listOf(movies, music), listOf(SidebarLibraryPref(9, true), SidebarLibraryPref(1, true)))
        assertEquals(listOf(1, 3), e.map { it.id })
        assertTrue(e.last().visible)
    }
}

class UiPrefsTest {
    @Test fun roundTripsAndSurvivesACorruptFile(@TempDir dir: Path) {
        val f = dir.resolve("ui.json")
        UiPrefs(f).sidebar = listOf(SidebarLibraryPref(2, false))
        assertEquals(listOf(SidebarLibraryPref(2, false)), UiPrefs(f).sidebar)
        Files.writeString(f, "{broken")
        assertEquals(emptyList(), UiPrefs(f).sidebar)
        assertEquals("auto", UiPrefs(f).hdrMode)
    }
}
```

(`@TempDir` needs `testImplementation("org.junit.jupiter:junit-jupiter-api")`, already on the classpath via `kotlin("test")` with JUnit 5. If not, use `Files.createTempDirectory`.)

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Order: preference order for known ids, then the remaining server libraries by `sortOrder`. Kind: `movie`/`movies` → Movies; `series`/`show`/`shows`/`tv` → Shows; `music` → Music; else Other. `UiPrefs` reads and writes a small `@Serializable data class UiPrefsFile(val sidebar: List<SidebarLibraryPref> = emptyList(), val hdrMode: String = "auto")`, writing to a temp file plus an atomic move, and falls back to defaults on any read error.
- [ ] **Step 4: Run tests to verify they pass.**
- [ ] **Step 5: Commit** `feat(app): sidebar library entries with device preferences`

---

### Task 4: Card models

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/browse/CardModel.kt`
- Test: `app/src/test/kotlin/dev/silage/app/browse/CardModelTest.kt`

**Interfaces:**
- Consumes: `SectionItem` and `BrowseItem` (fields: `contentId, type, title, seriesTitle?, seasonNumber?, episodeNumber?, year, positionSeconds?, durationSeconds?, posterUrl?, posterThumbhash?, backdropUrl?, backdropThumbhash?, logoUrl?, userState?`), `ResolvedSection.sectionType`.
- Produces: `data class CardModel(val id: String, val title: String, val caption: String?, val imageUrl: String?, val thumbhash: String?, val landscape: Boolean, val progress: Float?, val watched: Boolean, val backdropUrl: String?, val logoUrl: String?, val openTarget: String)`; `fun SectionItem.toCard(landscape: Boolean): CardModel`; `fun BrowseItem.toCard(): CardModel`; `fun isProgressRow(sectionType: String): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
class CardModelTest {
    @Test fun episodeInContinueWatchingIsLandscapeWithProgressAndSeriesCaption() {
        val item = SectionItem(contentId = "e1", type = "episode", title = "Pilot", seriesId = "s1", seriesTitle = "Severance",
            seasonNumber = 1, episodeNumber = 3, positionSeconds = 600.0, durationSeconds = 2400.0,
            posterUrl = "http://s/p.jpg", backdropUrl = "http://s/b.jpg")
        val c = item.toCard(landscape = true)
        assertEquals("Severance", c.title)
        assertEquals("S1 E3 · Pilot", c.caption)
        assertEquals("http://s/b.jpg", c.imageUrl)
        assertEquals(0.25f, c.progress)
        assertEquals("e1", c.openTarget)
    }

    @Test fun movieRowCardIsAPosterWithItsYear() {
        val c = SectionItem(contentId = "m1", type = "movie", title = "Dune", year = 2021, posterUrl = "http://s/p.jpg").toCard(landscape = false)
        assertEquals("Dune", c.title); assertEquals("2021", c.caption); assertEquals("http://s/p.jpg", c.imageUrl); assertNull(c.progress)
    }

    @Test fun progressRowsAreRecognised() {
        assertTrue(isProgressRow("continue_watching")); assertTrue(isProgressRow("next_up")); assertFalse(isProgressRow("recently_added"))
    }

    @Test fun missingDurationMeansNoProgressBar() {
        assertNull(SectionItem(contentId = "m", type = "movie", title = "X", positionSeconds = 30.0).toCard(true).progress)
    }
}
```

(Construct `SectionItem` with named arguments; check its required parameters in `shared/.../model/section/SectionModels.kt` and add any that have no default.)

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Landscape cards use the backdrop (falling back to the poster); portrait cards use the poster. Progress is `position / duration` clamped to 0..1 when both are positive. For episodes, the title is the series title and the caption is `S{n} E{m} · {title}`. `watched = userState?.played == true`. `isProgressRow` matches `continue_watching`, `in_progress`, `next_up`, `up_next`.
- [ ] **Step 4: Run tests to verify they pass.**
- [ ] **Step 5: Commit** `feat(app): card models for rows and grids`

---

### Task 5: App shell with the sidebar, and Home

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/shell/AppShell.kt` (sidebar + content, expand/collapse, focus hand-off)
- Create: `app/src/main/kotlin/dev/silage/app/shell/Sidebar.kt`
- Create: `app/src/main/kotlin/dev/silage/app/browse/PosterCard.kt` (`PosterCard`, `LandscapeCard`, `CardRow`)
- Create: `app/src/main/kotlin/dev/silage/app/home/HomeScreen.kt` (replaces `HomeListScreen.kt`, which is deleted)
- Modify: `app/src/main/kotlin/dev/silage/app/Main.kt` (theme, density, shell)

**Interfaces:**
- Consumes: Task 1 `AppNavigator`/`Route`, Task 2 `Artwork` and tokens, Task 3 `SidebarEntries`/`UiPrefs`, Task 4 `CardModel`, `HomeViewModel.uiState`/`loadSections()`, and `ProfileRepository.getActiveProfile()` (avatar, name).
- Produces: `@Composable fun AppShell(nav: AppNavigator, koin: Koin, content: @Composable () -> Unit)`, and `@Composable fun CardRow(title: String, cards: List<CardModel>, landscape: Boolean, onOpen: (CardModel) -> Unit, onFocus: (CardModel) -> Unit)`.

Layout, in 1920×1080 dp:
- Sidebar rail 96 dp wide, black at 92 %. Expanded 320 dp, drawn over the content with a horizontal gradient from black to transparent behind it. It expands while any entry has focus or the pointer is over it; animated over 180 ms.
- Entry: 56 dp high, 24 dp icon (Material icons: `Search`, `Home`, `Movie`, `Tv`, `MusicNote`, `VideoLibrary`, `BookmarkBorder`, `Settings`), 20 sp label. The current page's entry gets an amber 4 dp bar on its left edge. The focused entry inverts (white pill, black icon and label).
- Avatar at the top: 48 dp circle (`Artwork` of `avatarUrl`, or the initial on elevated), name when expanded.
- Home: a marquee in the top 56 % of the page, showing the focused card's backdrop (full-bleed, a gradient fading to black at the bottom and left), its logo (or a 56 sp title), and a caption line. Rows below scroll vertically. Row titles are 24 sp, Text2. Poster cards are 200×300 dp; landscape cards 400×225 dp with a 4 dp amber progress bar along the bottom edge. Rows scroll horizontally, keeping the focused card in view (`LazyRow` with `animateScrollToItem`).
- Keys: ← on the first card of a row moves focus to the sidebar (`FocusRequester`); → or Enter in the sidebar returns focus to the content. Esc on a non-Home root page calls `nav.back()`.
- Loading: the marquee and row skeletons are Elevated rectangles. Errors: a centered message with a Retry button.

- [ ] **Step 1:** Build `PosterCard`/`LandscapeCard`/`CardRow` and the shell. No logic beyond Tasks 1–4, so no new unit tests. Verify visually with `./gradlew :app:run` against the user's server. Take a screenshot with `spectacle -b -n -a -o /tmp/claude-1000/...scratchpad/home.png` and check it: posters load, the sidebar lists every library, the focus ring is visible, the marquee changes with focus.
- [ ] **Step 2:** Run `./gradlew :app:test`. Expected: PASS.
- [ ] **Step 3: Commit** `feat(app): sidebar shell and poster Home`

---

### Task 6: Library pages with paging, sort, and collections

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/browse/LibraryPager.kt`
- Create: `app/src/main/kotlin/dev/silage/app/browse/LibraryScreen.kt`, `CollectionScreen.kt`
- Test: `app/src/test/kotlin/dev/silage/app/browse/LibraryPagerTest.kt`

**Interfaces:**
- Consumes: `CatalogRepository.browse(libraryId=, sort=, order=, continuation=, limit=)` → `ApiResult<CatalogResponse(items, hasMore, continuation)>`; `SectionRepository.getLibraryCollections(libraryId)`; `SectionRepository.getLibraryCollectionItems(collectionId, continuation, limit)`.
- Produces: `class LibraryPager(private val fetch: suspend (CatalogContinuationV2?) -> ApiResult<CatalogResponse>)` with `val state: StateFlow<PagerState>` and `suspend fun loadMore()`, `suspend fun retry()`. `data class PagerState(val items: List<CardModel>, val loading: Boolean, val endReached: Boolean, val error: String?)`. `enum class LibrarySort(val key: String, val order: String, val label: String) { Added("added_at","desc","Recently added"), Title("title","asc","Title"), Release("release_date","desc","Release date"), Rating("rating_imdb","desc","Rating") }`.

- [ ] **Step 1: Write the failing test**

```kotlin
class LibraryPagerTest {
    private fun page(ids: List<String>, more: Boolean, next: String?) = ApiResult.Success(
        CatalogResponse(total = 99, hasMore = more, items = ids.map { BrowseItem(contentId = it, type = "movie", title = it) },
            continuation = next?.let { CatalogContinuationV2(it) }))

    @Test fun appendsPagesUntilTheEnd() = runTest {
        val pages = ArrayDeque(listOf(page(listOf("a", "b"), true, "c1"), page(listOf("c"), false, null)))
        val asked = mutableListOf<CatalogContinuationV2?>()
        val p = LibraryPager { asked += it; pages.removeFirst() }
        p.loadMore(); p.loadMore(); p.loadMore()
        assertEquals(listOf("a", "b", "c"), p.state.value.items.map { it.id })
        assertTrue(p.state.value.endReached)
        assertEquals(listOf(null, CatalogContinuationV2("c1")), asked)
    }

    @Test fun duplicateItemsAcrossPagesAreDropped() = runTest {
        val pages = ArrayDeque(listOf(page(listOf("a", "b"), true, "x"), page(listOf("b", "c"), false, null)))
        val p = LibraryPager { pages.removeFirst() }
        p.loadMore(); p.loadMore()
        assertEquals(listOf("a", "b", "c"), p.state.value.items.map { it.id })
    }

    @Test fun aFailedPageKeepsItemsAndRetriesTheSameContinuation() = runTest {
        val results = ArrayDeque<ApiResult<CatalogResponse>>(listOf(page(listOf("a"), true, "k"), ApiResult.NetworkError(Exception("x")), page(listOf("b"), false, null)))
        val asked = mutableListOf<CatalogContinuationV2?>()
        val p = LibraryPager { asked += it; results.removeFirst() }
        p.loadMore(); p.loadMore()
        assertEquals(listOf("a"), p.state.value.items.map { it.id }); assertNotNull(p.state.value.error)
        p.retry()
        assertEquals(listOf("a", "b"), p.state.value.items.map { it.id }); assertNull(p.state.value.error)
        assertEquals(CatalogContinuationV2("k"), asked[2])
    }

    @Test fun emptyLibraryEndsImmediately() = runTest {
        val p = LibraryPager { page(emptyList(), false, null) }
        p.loadMore()
        assertTrue(p.state.value.endReached && p.state.value.items.isEmpty() && !p.state.value.loading)
    }
}
```

(Check `CatalogContinuationV2`'s and `ApiResult.NetworkError`'s constructors in `shared` and match them.)

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** `LibraryPager` (a `Mutex` so concurrent `loadMore` calls don't double-fetch; dedupe by `contentId`). Build `LibraryScreen`: a title header, a pill row (Browse · Collections · Recently added), a sort menu, and a `LazyVerticalGrid` of 200×300 dp posters, 7 per row at 1080p, which calls `loadMore()` when focus or scroll comes within two rows of the end. The focused poster's backdrop fades in behind the page at 25 % opacity. The empty state reads "Nothing here yet". The error row reads "Couldn't load more" with a Retry button. The Collections pill shows a grid of collections; opening one goes to `Route.Collection`, a grid over `getLibraryCollectionItems`.
- [ ] **Step 4: Run tests to verify they pass;** check a library visually.
- [ ] **Step 5: Commit** `feat(app): library grids with paging, sort and collections`

---

### Task 7: Detail pages (movie and series)

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/detail/DetailModel.kt`, `DetailScreen.kt`
- Test: `app/src/test/kotlin/dev/silage/app/detail/DetailModelTest.kt`

**Interfaces:**
- Consumes: `CatalogRepository.getItemDetail(contentId)` → `ItemDetail(...)`; `getSeasons(seriesId)` → `SeasonsResponse` (use `sortedForDisplay()` and `initialSeasonDisplayPlan`); `getEpisodes(seriesId, seasonNumber)` → `EpisodesResponse(episodes: List<EpisodeListItem>)`.
- Produces: `data class DetailModel(val title: String, val logoUrl: String?, val backdropUrl: String?, val backdropThumbhash: String?, val metaLine: String, val tagline: String?, val overview: String?, val playLabel: String, val canPlayFromStart: Boolean, val cast: List<CastCard>, val isSeries: Boolean)`; `fun ItemDetail.toDetailModel(): DetailModel`; `data class EpisodeRow(val id: String, val number: String, val title: String, val overview: String?, val stillUrl: String?, val stillThumbhash: String?, val runtime: String?, val progress: Float?, val watched: Boolean)`; `fun EpisodeListItem.toRow(): EpisodeRow`.

- [ ] **Step 1: Write the failing test**

```kotlin
class DetailModelTest {
    @Test fun movieMetaLineAndResumeLabel() {
        val d = ItemDetail(contentId = "m", type = "movie", title = "Dune", year = 2021, runtime = 155, contentRating = "PG-13",
            genres = listOf("Sci-Fi", "Adventure"), userData = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 2530.0, durationSeconds = 9300.0))
        val m = d.toDetailModel()
        assertEquals("2021 · 2h 35m · PG-13 · Sci-Fi, Adventure", m.metaLine)
        assertEquals("Resume from 42:10", m.playLabel)
        assertTrue(m.canPlayFromStart)
    }

    @Test fun unwatchedMovieJustPlays() {
        val m = ItemDetail(contentId = "m", type = "movie", title = "X").toDetailModel()
        assertEquals("Play", m.playLabel); assertFalse(m.canPlayFromStart)
    }

    @Test fun episodeRowShowsNumberRuntimeAndProgress() {
        val r = EpisodeListItem(contentId = "e", seasonNumber = 1, episodeNumber = 4, title = "The You You Are", runtime = 51,
            userData = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 1530.0, durationSeconds = 3060.0)).toRow()
        assertEquals("4", r.number); assertEquals("51m", r.runtime); assertEquals(0.5f, r.progress)
    }
}
```

(Construct the shared models with named arguments; supply any required parameters that have no default.)

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** the models, then `DetailScreen`:
  - A full-bleed backdrop with a gradient fading to black at the left and bottom.
  - Logo (max 560×200 dp) or the title.
  - The meta line, then the tagline in italics, then the overview (4 lines).
  - Buttons: Play/Resume (white, inverted on focus), Play from beginning (outlined), shown only when there is something to resume.
  - A cast rail of 120 dp circles with names.
  - Series add season pills under the buttons and an episode list: rows with a 320×180 still, number and title, runtime, overview, and an amber progress bar. Enter on an episode plays it. The initial season comes from `initialSeasonDisplayPlan`.
- [ ] **Step 4: Run tests to verify they pass;** check a movie and a series visually.
- [ ] **Step 5: Commit** `feat(app): movie and series detail pages`

---

### Task 8: Play from the right position

`PlaybackLauncher` always passes `startSeconds = null`, so playback starts from 0 even for an in-progress item.

**Files:**
- Modify: `app/src/main/kotlin/dev/silage/app/playback/PlaybackLauncher.kt`
- Modify: `app/src/main/kotlin/dev/silage/app/playback/PlayingScreen.kt` (pass `fromStart`)
- Test: `app/src/test/kotlin/dev/silage/app/playback/PlayStartTest.kt`

**Interfaces:**
- Produces: `fun playStart(userData: LeafItemUserData?, fromStart: Boolean): Double?`; `PlaybackLauncher.launch(contentId: String, fromStart: Boolean = false)`.

- [ ] **Step 1: Write the failing test**

```kotlin
class PlayStartTest {
    private val inProgress = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 754.0, durationSeconds = 6000.0)
    @Test fun resumesAnInProgressItem() = assertEquals(754.0, playStart(inProgress, fromStart = false))
    @Test fun fromStartWins() = assertEquals(null, playStart(inProgress, fromStart = true))
    @Test fun watchedItemsStartOver() = assertEquals(null, playStart(inProgress.copy(played = true), false))
    @Test fun noUserDataStartsAtZero() = assertEquals(null, playStart(null, false))
}
```

Also add a launcher test: `WatchDetail(userData = inProgress)` → `Ready.startSeconds == 754.0`.

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** (`positionSeconds` when `!played && (isInProgress == true || position > 0)` and `!fromStart`, else null). Thread `fromStart` from `Route.Playing` through `PlaybackSession.play` into `launch`.
- [ ] **Step 4: Run tests to verify they pass.**
- [ ] **Step 5: Commit** `fix(app): resume playback from the saved position`

---

### Task 9: Search and Watchlist

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/browse/SearchScreen.kt`, `WatchlistScreen.kt`
- Create: `app/src/main/kotlin/dev/silage/app/browse/SearchQuery.kt`
- Test: `app/src/test/kotlin/dev/silage/app/browse/SearchQueryTest.kt`

**Interfaces:**
- Consumes: `CatalogRepository.browse(query = q, limit = 60)`; `LibraryPager` (Task 6) with `browse(source = "watchlist", continuation = it)`.
- Produces: `class SearchQuery(debounceMs: Long = 300, search: suspend (String) -> ApiResult<CatalogResponse>)` with `fun onInput(text: String)` and `val results: StateFlow<SearchResults>` (`Idle`, `Loading`, `Done(cards)`, `Failed(message)`), keeping only the latest query's result.

- [ ] **Step 1: Write the failing test** (`kotlinx-coroutines-test` virtual time): typing "d", "du", "dune" within 300 ms issues one search for "dune"; a blank query returns to `Idle` without searching; a slow earlier response never overwrites a newer one.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** `SearchScreen` is a large text field (focused on open, typing from a physical keyboard) over a poster grid of results. `WatchlistScreen` is a `LibraryPager` grid.
- [ ] **Step 4: Run tests to verify they pass.**
- [ ] **Step 5: Commit** `feat(app): search and watchlist`

---

### Task 10: Settings

**Files:**
- Create: `app/src/main/kotlin/dev/silage/app/settings/SettingsScreen.kt`
- Create: `app/src/main/kotlin/dev/silage/app/settings/SidebarEditor.kt`
- Modify: `app/src/main/kotlin/dev/silage/app/playback/PlayingScreen.kt` (HDR mode from `UiPrefs.hdrMode`; the `SILAGE_HDR` env var still overrides)
- Test: `app/src/test/kotlin/dev/silage/app/settings/SidebarEditorTest.kt`

**Interfaces:**
- Consumes: Task 3 `SidebarEntries`, `UiPrefs`.
- Produces: `object SidebarEditor { fun toggle(prefs: List<SidebarLibraryPref>, all: List<SidebarLibrary>, id: Int): List<SidebarLibraryPref>; fun move(prefs, all, id: Int, delta: Int): List<SidebarLibraryPref> }`. Results always hold every library, in order.

Sections, in a left list and a right detail pane:
- **Interface → Sidebar:** each library with a visibility switch and move up/down.
- **Video:** HDR (Auto: detect the monitor / Always / Never).
- **Account:** switch profile (`nav.root(Route.Profiles)`), switch server (`Route.Connect`), sign out (clear tokens via `AuthRepository`, then `Route.SignIn`).
- **About:** version, server URL, log folder path.

Settings that aren't built yet are not shown. Their spec rows land with Plan 3.

- [ ] **Step 1: Write the failing test:** toggling hides only that id; moving the first entry up is a no-op; moving swaps neighbours; a library missing from the prefs is materialised at its displayed position.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**, saving through `UiPrefs` on each change. The sidebar re-reads the prefs, so the change appears immediately.
- [ ] **Step 4: Run tests to verify they pass.**
- [ ] **Step 5: Commit** `feat(app): settings with sidebar editor, HDR mode and account actions`

---

### Task 11: First-run and profile screens restyled

**Files:**
- Modify: `app/src/main/kotlin/dev/silage/app/connect/ConnectScreen.kt`, `signin/SignInScreen.kt`, `profiles/ProfilesScreen.kt`

- [ ] **Step 1:** Restyle with the tokens:
  - **Connect / Sign in:** a centered 560 dp column on black, the Silage wordmark (text, 48 sp, letter-spaced), large fields with white focus rings, and one primary button.
  - **Profiles:** "Who's watching?" (40 sp) over a row of 160 dp avatar circles (`Artwork` of `avatarUrl`, keyed by the `avatar` ref so presigned-URL changes don't defeat the cache) with names. A focused avatar scales to 1.1 with a white ring. The PIN pad is a 3×4 grid of 88 dp keys that invert on focus.
  - Existing presenters and their tests are unchanged.
- [ ] **Step 2:** Run `./gradlew :app:test` (PASS), then check visually.
- [ ] **Step 3: Commit** `feat(app): restyle connect, sign-in and profile screens`

---

## Out of scope (later plans)

Favorites/watchlist toggles on cards and pages (these need the durable `MembershipPort` that replaces `UnavailableMembershipPort`), gamepad input, phone pairing, the remaining Settings rows, the Next Up card in the player, and trickplay.
