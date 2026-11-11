package dev.silage.app.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.CardModel
import dev.silage.app.browse.PageBackdrop
import dev.silage.app.browse.PagerState
import dev.silage.app.browse.PosterGrid
import dev.silage.app.browse.toCard
import dev.silage.app.nav.rememberEntryViewModel
import dev.silage.app.ui.Silage
import dev.silage.app.ui.rememberInitialFocus
import org.koin.core.Koin
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.viewmodel.SectionBrowseArgs
import org.siloserver.silo.viewmodel.SectionBrowseViewModel

/** A Home or library row's full page ("Explore all"), paged from the server. */
@Composable
fun SectionPageScreen(koin: Koin, sectionId: String, title: String, libraryId: Int? = null, onOpen: (CardModel) -> Unit) {
    val vm = rememberEntryViewModel { SectionBrowseViewModel(koin.get(), SectionBrowseArgs(sectionId, title, libraryId)) }
    val state by vm.uiState.collectAsState()
    val cards = remember(state.items) { state.items.map { it.toCard() } }
    val grid = PagerState(
        items = cards,
        loading = state.isLoading || state.isLoadingMore,
        endReached = !state.hasMore,
        error = when {
            state.unavailable -> "This row is no longer available."
            else -> (state.error ?: state.loadMoreError)?.text()
        },
    )
    var focused by remember { mutableStateOf<CardModel?>(null) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val first = rememberInitialFocus(cards.isNotEmpty())
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        PosterGrid(grid, onLoadMore = { vm.loadMore() }, onRetry = { if (state.items.isEmpty()) vm.retry() else vm.loadMore() },
            onOpen = { lastFocused = it.id; onOpen(it) }, onFocus = { focused = it; lastFocused = it.id },
            emptyText = "Nothing in this row right now", firstFocus = first, restoreId = lastFocused,
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(title, color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 56.dp, bottom = 12.dp))
                }
            })
    }
}
