package dev.silage.app.browse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import dev.silage.app.nav.rememberEntry
import dev.silage.app.ui.rememberInitialFocus
import org.koin.core.Koin
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.network.map
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.SectionRepository

enum class LibrarySort(val key: String, val order: String, val label: String) {
    Added("added_at", "desc", "Recently added"),
    Title("title", "asc", "Title"),
    Release("release_date", "desc", "Release date"),
    Rating("rating_imdb", "desc", "Rating"),
}

suspend fun ApiResult<CatalogResponse>.toPage(): ApiResult<Page<CatalogContinuationV2>> =
    map { r -> Page(r.items.map { it.toCard() }, r.hasMore, r.continuation) }

/** A library: Browse (poster grid with sort) or Collections. */
@Composable
fun LibraryScreen(koin: Koin, libraryId: Int, name: String, onOpen: (CardModel) -> Unit, onOpenCollection: (String, String) -> Unit,
    onShuffle: (contentId: String, shuffleId: String) -> Unit = { _, _ -> }) {
    val shuffle = dev.silage.app.playback.rememberShuffleStart(koin, org.siloserver.silo.model.shuffle.ShuffleScopeKind.LIBRARY, libraryId.toString(), onShuffle)
    // Saved with this visit's back-stack entry, so Back from a detail page returns here intact.
    var tab by rememberSaveable { mutableStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Added) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember(libraryId) { mutableStateOf<CardModel?>(null) }
    val scope = rememberCoroutineScope()
    val catalog = remember { koin.get<CatalogRepository>() }
    val sections = remember { koin.get<SectionRepository>() }

    val pager = rememberEntry("library:$libraryId:$tab:$sort") {
        if (tab == 0) {
            LibraryPager<CatalogContinuationV2> { c -> catalog.browse(libraryId = libraryId, sort = sort.key, order = sort.order, continuation = c, limit = 60).toPage() }
        } else {
            LibraryPager<Unit> {
                sections.getLibraryCollections(libraryId).map { list ->
                    Page(list.map { c ->
                        CardModel(c.id, c.name, c.itemCount?.let { "$it items" }, c.posterUrl, c.posterThumbhash, false, null, false,
                            null, null, null, null, "", c.id)
                    }, hasMore = false, next = null)
                }
            }
        }
    }
    val state by pager.state.collectAsState()
    LaunchedEffect(pager) { if (pager.state.value.items.isEmpty()) pager.loadMore() }
    // Initial focus once per visit: changing tab or sort keeps focus on the pill.
    val first = rememberInitialFocus(state.items.isNotEmpty())

    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        // Each tab/sort keeps its own scroll position.
        key(tab, sort) { PosterGrid(
            state,
            onLoadMore = { scope.launch { pager.loadMore() } },
            onRetry = { scope.launch { pager.retry() } },
            onOpen = { if (tab == 0) onOpen(it) else onOpenCollection(it.id, it.title) },
            onFocus = { focused = it; lastFocused = it.id },
            emptyText = if (tab == 0) "Nothing here yet" else "No collections in this library",
            firstFocus = first,
            restoreId = lastFocused,
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 56.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(name, color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Pill("Browse", selected = tab == 0) { tab = 0 }
                            Pill("Collections", selected = tab == 1) { tab = 1 }
                            if (tab == 0) {
                                Spacer(Modifier.width(18.dp))
                                Pill("Sort: ${sort.label}") { sort = LibrarySort.entries[(sort.ordinal + 1) % LibrarySort.entries.size] }
                                shuffle?.let { start -> Pill("Shuffle", onClick = start) }
                            }
                        }
                    }
                }
            },
        ) }
    }
}

/** One collection's items as a poster grid. */
@Composable
fun CollectionScreen(koin: Koin, collectionId: String, name: String, onOpen: (CardModel) -> Unit,
    onShuffle: (contentId: String, shuffleId: String) -> Unit = { _, _ -> }) {
    val shuffle = dev.silage.app.playback.rememberShuffleStart(koin, org.siloserver.silo.model.shuffle.ShuffleScopeKind.LIBRARY_COLLECTION, collectionId, onShuffle)
    val sections = remember { koin.get<SectionRepository>() }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<CardModel?>(null) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val pager = rememberEntry("collection:$collectionId") {
        LibraryPager<CatalogContinuationV2> { c -> sections.getLibraryCollectionItems(collectionId, continuation = c, limit = 60).toPage() }
    }
    val state by pager.state.collectAsState()
    LaunchedEffect(pager) { if (pager.state.value.items.isEmpty()) pager.loadMore() }
    val first = rememberInitialFocus(state.items.isNotEmpty())
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        PosterGrid(state, { scope.launch { pager.loadMore() } }, { scope.launch { pager.retry() } }, onOpen, { focused = it; lastFocused = it.id },
            firstFocus = first, restoreId = lastFocused, header = {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 56.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(name, color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    shuffle?.let { start -> Pill("Shuffle", onClick = start) }
                }
            }
        })
    }
}
