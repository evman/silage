package dev.silage.app.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import dev.silage.app.nav.LocalNavEntry
import dev.silage.app.nav.rememberEntry
import dev.silage.app.ui.rememberInitialFocus
import org.koin.core.Koin
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.repository.CatalogRepository
import androidx.compose.foundation.lazy.grid.items
import dev.silage.app.requests.RequestSearch
import dev.silage.app.requests.openRequestCard
import dev.silage.app.requests.requestCardId
import dev.silage.app.requests.toRequestCard
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.RequestsRepository

/** Search as you type over the whole server. */
@Composable
fun SearchScreen(koin: Koin, onOpen: (CardModel) -> Unit, onOpenRequest: (mediaType: String, tmdbId: Int) -> Unit = { _, _ -> }) {
    val catalog = remember { koin.get<CatalogRepository>() }
    // Titles the server can fetch, under the library results, when Requests is on.
    val requestsEnabled by remember { koin.get<RequestsFeatureStore>() }.isEnabled.collectAsState()
    val requestRepo = remember { koin.get<RequestsRepository>() }
    // Results and text live with this visit's back-stack entry: Back from a result returns to them.
    val entryScope = LocalNavEntry.current.scope
    val query = rememberEntry("search") { SearchQuery(entryScope) { catalog.browse(query = it, limit = 60) } }
    val results by query.results.collectAsState()
    val requestSearch = rememberEntry("search:requests") {
        RequestSearch(entryScope, { q ->
            when (val r = requestRepo.search(query = q)) {
                is ApiResult.Success -> ApiResult.Success(r.data.results)
                is ApiResult.Error -> r
                is ApiResult.NetworkError -> r
            }
        }, requestRepo.lastUpdate)
    }
    val requestResults by requestSearch.results.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<CardModel?>(null) }
    val field = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Coming back from a result: focus it; otherwise start typing.
        if (lastFocused != null && results is SearchResults.Done) runCatching { restore.requestFocus() }.onFailure { runCatching { field.requestFocus() } }
        else runCatching { field.requestFocus() }
    }

    val state = when (val r = results) {
        SearchResults.Idle -> PagerState(endReached = false)
        SearchResults.Loading -> PagerState(loading = true)
        is SearchResults.Done -> PagerState(items = r.cards, endReached = true)
        is SearchResults.Failed -> PagerState(endReached = true, error = r.message)
    }
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        PosterGrid(
            state = if (results == SearchResults.Idle) PagerState(endReached = true) else state,
            onLoadMore = {}, onRetry = { query.onInput(text) }, onOpen = onOpen, onFocus = { focused = it; lastFocused = it.id },
            firstFocus = restore, restoreId = lastFocused,
            emptyText = if (results == SearchResults.Idle) "Search your movies and shows" else "No results for “$text” in your library",
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchField(text, { text = it; query.onInput(it); if (requestsEnabled) requestSearch.onInput(it) }, field)
                }
            },
            footer = {
                if (requestsEnabled && text.isNotBlank() && requestResults.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 16.dp)) {
                            Text("Not in your library", color = Silage.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("Request a title and the server will add it.", color = Silage.Text2, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    items(requestResults, key = { requestCardId(it.mediaType, it.tmdbId) }) { r ->
                        val card = remember(r) { r.toRequestCard() }
                        MediaCard(card, 200.dp, onOpen = { lastFocused = card.id; openRequestCard(card, { onOpen(card.copy(openTarget = it)) }, onOpenRequest) },
                            onFocus = { focused = card })
                    }
                }
            },
        )
    }
}

/** The big search box. */
@Composable
fun SearchField(text: String, onChange: (String) -> Unit, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val fieldFocused by interaction.collectIsFocusedAsState()
    Row(
        modifier.padding(top = 56.dp, bottom = 20.dp).widthIn(max = 1100.dp).fillMaxWidth().height(76.dp)
            .clip(RoundedCornerShape(38.dp)).background(Color.White.copy(alpha = 0.10f))
            .border(3.dp, if (fieldFocused) Color.White else Color.Transparent, RoundedCornerShape(38.dp))
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = Silage.Text2, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(18.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Movies, shows, people…", color = Silage.Text3, fontSize = 26.sp)
            BasicTextField(
                text, onValueChange = onChange,
                singleLine = true, interactionSource = interaction,
                textStyle = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Silage.Amber),
                // A single-line field keeps the arrows for its cursor; Down/Enter go to the results.
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                        focus.moveFocus(FocusDirection.Down)
                    } else {
                        false
                    }
                },
            )
        }
    }
}

/** The profile's watchlist as a poster grid. */
@Composable
fun WatchlistScreen(koin: Koin, onOpen: (CardModel) -> Unit) =
    PersonalListScreen(koin, "watchlist", "Watchlist", "Your watchlist is empty", onOpen)

/** The titles the profile marked as favorites. */
@Composable
fun FavoritesScreen(koin: Koin, onOpen: (CardModel) -> Unit) =
    PersonalListScreen(koin, "favorites", "Favorites", "You haven't marked any favorites yet", onOpen)

/** What the profile watched lately, most recent first. */
@Composable
fun HistoryScreen(koin: Koin, onOpen: (CardModel) -> Unit) =
    PersonalListScreen(koin, "history", "History", "Nothing watched yet", onOpen, removableFromHistory = true)

/** One of the profile's own lists (the catalog's `source`) as a titled poster grid. */
@Composable
private fun PersonalListScreen(koin: Koin, source: String, title: String, emptyText: String, onOpen: (CardModel) -> Unit,
    /** History: the right-click menu also offers Remove from history. */
    removableFromHistory: Boolean = false) {
    val catalog = remember { koin.get<CatalogRepository>() }
    // Titles removed from history on this visit: gone from the grid at once.
    var removed by remember { mutableStateOf(emptySet<String>()) }
    val base = LocalCardMenu.current
    val menu = remember(base, removableFromHistory) {
        if (!removableFromHistory || base == null) base
        else base.forHistory(
            remove = { card ->
                when (val r = koin.get<org.siloserver.silo.network.apiv2.HistoryRemovalV2Api>().remove(card.id)) {
                    is org.siloserver.silo.network.ApiResult.Success -> { removed = removed + card.id; null }
                    is org.siloserver.silo.network.ApiResult.Error -> "Couldn't remove it: ${r.message.ifBlank { "the server said no" }}"
                    is org.siloserver.silo.network.ApiResult.NetworkError -> "Can't reach the server."
                }
            },
            removed = {},
        )
    }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<CardModel?>(null) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val pager = rememberEntry(source) { LibraryPager<CatalogContinuationV2> { c -> catalog.browse(source = source, continuation = c, limit = 60).toPage() } }
    val state by pager.state.collectAsState()
    LaunchedEffect(pager) { if (pager.state.value.items.isEmpty()) pager.loadMore() }
    val first = rememberInitialFocus(state.items.isNotEmpty())
    val shown = remember(state, removed) { withoutRemoved(state, removed) }
    androidx.compose.runtime.CompositionLocalProvider(LocalCardMenu provides menu) {
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        PosterGrid(shown, { scope.launch { pager.loadMore() } }, { scope.launch { pager.retry() } }, onOpen, { focused = it; lastFocused = it.id },
            emptyText = emptyText, firstFocus = first, restoreId = lastFocused, header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(title, color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 56.dp, bottom = 12.dp))
                }
            })
    }
    }
}

/** The grid without the cards in [removed] (by id). */
internal fun withoutRemoved(state: PagerState, removed: Set<String>): PagerState =
    if (removed.isEmpty()) state else state.copy(items = state.items.filterNot { it.id in removed })
