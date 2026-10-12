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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.repository.CatalogRepository

/** Search as you type over the whole server. */
@Composable
fun SearchScreen(koin: Koin, onOpen: (CardModel) -> Unit) {
    val catalog = remember { koin.get<CatalogRepository>() }
    val scope = rememberCoroutineScope()
    val query = remember { SearchQuery(scope) { catalog.browse(query = it, limit = 60) } }
    val results by query.results.collectAsState()
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<CardModel?>(null) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

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
            onLoadMore = {}, onRetry = { query.onInput(text) }, onOpen = onOpen, onFocus = { focused = it },
            emptyText = if (results == SearchResults.Idle) "Search your movies and shows" else "No results for “$text”",
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val interaction = remember { MutableInteractionSource() }
                    val fieldFocused by interaction.collectIsFocusedAsState()
                    Row(
                        Modifier.padding(top = 56.dp, bottom = 20.dp).widthIn(max = 1100.dp).fillMaxWidth().height(76.dp)
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
                                text, onValueChange = { text = it; query.onInput(it) },
                                singleLine = true, interactionSource = interaction,
                                textStyle = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium),
                                cursorBrush = SolidColor(Silage.Amber),
                                modifier = Modifier.fillMaxWidth().focusRequester(field),
                            )
                        }
                    }
                }
            },
        )
    }
}

/** The profile's watchlist as a poster grid. */
@Composable
fun WatchlistScreen(koin: Koin, onOpen: (CardModel) -> Unit) {
    val catalog = remember { koin.get<CatalogRepository>() }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<CardModel?>(null) }
    val pager = remember { LibraryPager<CatalogContinuationV2> { c -> catalog.browse(source = "watchlist", continuation = c, limit = 60).toPage() } }
    val state by pager.state.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(pager) { pager.loadMore() }
    LaunchedEffect(state.items.isNotEmpty()) { if (state.items.isNotEmpty()) runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        PosterGrid(state, { scope.launch { pager.loadMore() } }, { scope.launch { pager.retry() } }, onOpen, { focused = it },
            emptyText = "Your watchlist is empty", firstFocus = first, header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Watchlist", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 56.dp, bottom = 12.dp))
                }
            })
    }
}
