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

/** Search as you type over the whole server. */
@Composable
fun SearchScreen(koin: Koin, onOpen: (CardModel) -> Unit) {
    val catalog = remember { koin.get<CatalogRepository>() }
    // Results and text live with this visit's back-stack entry: Back from a result returns to them.
    val entryScope = LocalNavEntry.current.scope
    val query = rememberEntry("search") { SearchQuery(entryScope) { catalog.browse(query = it, limit = 60) } }
    val results by query.results.collectAsState()
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
            emptyText = if (results == SearchResults.Idle) "Search your movies and shows" else "No results for “$text”",
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchField(text, { text = it; query.onInput(it) }, field)
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
fun WatchlistScreen(koin: Koin, onOpen: (CardModel) -> Unit) {
    val catalog = remember { koin.get<CatalogRepository>() }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<CardModel?>(null) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val pager = rememberEntry("watchlist") { LibraryPager<CatalogContinuationV2> { c -> catalog.browse(source = "watchlist", continuation = c, limit = 60).toPage() } }
    val state by pager.state.collectAsState()
    LaunchedEffect(pager) { if (pager.state.value.items.isEmpty()) pager.loadMore() }
    val first = rememberInitialFocus(state.items.isNotEmpty())
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        PosterGrid(state, { scope.launch { pager.loadMore() } }, { scope.launch { pager.retry() } }, onOpen, { focused = it; lastFocused = it.id },
            emptyText = "Your watchlist is empty", firstFocus = first, restoreId = lastFocused, header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Watchlist", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 56.dp, bottom = 12.dp))
                }
            })
    }
}
