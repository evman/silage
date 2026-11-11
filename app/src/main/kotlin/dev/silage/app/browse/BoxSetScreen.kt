package dev.silage.app.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.nav.rememberEntryViewModel
import dev.silage.app.ui.Silage
import org.koin.core.Koin
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.model.catalog.BOX_SET_TYPE
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.boxSetFilmCount
import org.siloserver.silo.model.catalog.boxSetNextMovie
import org.siloserver.silo.model.catalog.cardCaption
import org.siloserver.silo.model.catalog.heroFacts
import org.siloserver.silo.model.catalog.playLabel
import org.siloserver.silo.model.catalog.yearsLabel
import org.siloserver.silo.viewmodel.BoxSetViewModel

/** A box set's content id, "box_set:<library id>:<collection id>", as upstream's search cards use. */
fun boxSetTarget(set: CatalogBoxSet): String = "$BOX_SET_TYPE:${set.libraryId}:${set.id}"

/** The library and collection ids in a [boxSetTarget], or null for any other content id. */
fun parseBoxSetTarget(contentId: String): Pair<String, String>? {
    val parts = contentId.split(':', limit = 3)
    return if (parts.size == 3 && parts[0] == BOX_SET_TYPE && parts[1].isNotEmpty() && parts[2].isNotEmpty()) parts[1] to parts[2] else null
}

/** A box set as a poster card: watched progress once started, else the years or the film count. */
fun CatalogBoxSet.toCard(): CardModel = CardModel(
    id = boxSetTarget(this),
    title = title,
    caption = cardCaption().text(),
    imageUrl = posterUrl,
    thumbhash = posterThumbhash,
    landscape = false,
    progress = null,
    watched = movieCount > 0 && playedCount >= movieCount,
    backdropUrl = backdropUrl,
    backdropThumbhash = backdropThumbhash,
    logoUrl = null,
    overview = overview,
    metaLine = listOfNotNull("Box set", boxSetFilmCount(movieCount).text(), yearsLabel()?.text()).joinToString(" · "),
    openTarget = boxSetTarget(this),
)

/**
 * A movie box set (a franchise such as Harry Potter): every film the viewer can see, in release
 * order, with a button that plays the next unwatched one.
 */
@Composable
fun BoxSetScreen(koin: Koin, libraryId: String, boxSetId: String, onOpen: (CardModel) -> Unit, onPlay: (contentId: String) -> Unit) {
    val vm = rememberEntryViewModel { BoxSetViewModel(koin.get(), boxSetId, libraryId) }
    val state by vm.uiState.collectAsState()
    // Coming back from a film re-reads the set, so watched progress and the play button follow.
    LaunchedEffect(Unit) { vm.onScreenResumed() }
    var focused by remember { mutableStateOf<CardModel?>(null) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = state.detail
    val cards = remember(detail) { detail?.items.orEmpty().map { it.toCard() } }
    val grid = when {
        detail != null -> PagerState(items = cards, endReached = true)
        state.unavailable -> PagerState(endReached = true, error = "This box set isn't available. Box sets may be turned off for this library, or too few of its movies are here.")
        state.error != null -> PagerState(endReached = true, error = state.error!!.text())
        else -> PagerState(loading = true)
    }
    val playFocus = remember { FocusRequester() }
    val next = detail?.let { boxSetNextMovie(it.items) }
    LaunchedEffect(next != null) { if (next != null) runCatching { playFocus.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused ?: detail?.boxSet?.toCard())
        PosterGrid(grid, onLoadMore = {}, onRetry = { vm.retry() }, onOpen = { lastFocused = it.id; onOpen(it) },
            onFocus = { focused = it; lastFocused = it.id }, emptyText = "No films in this box set yet", restoreId = lastFocused,
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val set = detail?.boxSet
                    Column(Modifier.padding(top = 56.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("BOX SET", color = Silage.Amber, fontSize = 14.sp, letterSpacing = 2.sp)
                        Text(set?.title ?: "…", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                        if (set != null) {
                            Text(set.heroFacts().joinToString(" · ") { it.text() }, color = Silage.Text2, fontSize = 19.sp)
                            set.overview?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = Silage.Text2, fontSize = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.dp))
                            }
                        }
                        if (set != null && next != null) {
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                dev.silage.app.detail.ActionButton(Icons.Filled.PlayArrow, set.playLabel(next).text(), Modifier.focusRequester(playFocus)) { onPlay(next.contentId) }
                            }
                        }
                    }
                }
            })
    }
}
