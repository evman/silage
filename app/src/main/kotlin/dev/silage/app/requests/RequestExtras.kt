package dev.silage.app.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.CardModel
import dev.silage.app.browse.PageBackdrop
import dev.silage.app.browse.PagerState
import dev.silage.app.browse.Pill
import dev.silage.app.browse.PosterGrid
import dev.silage.app.nav.rememberEntryViewModel
import dev.silage.app.ui.Silage
import dev.silage.app.ui.rememberInitialFocus
import org.koin.core.Koin
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.model.request.requestReasonText
import org.siloserver.silo.viewmodel.MyRequestsViewModel
import org.siloserver.silo.viewmodel.RequestDiscoverRowViewModel

/** A Discover row's trailing card that opens the whole row. */
internal fun seeAllCard(section: RequestDiscoverySection) = CardModel(
    id = "see-all:${section.key}", title = "See all", caption = section.title, imageUrl = null, thumbhash = null,
    landscape = false, progress = null, watched = false, backdropUrl = null, backdropThumbhash = null, logoUrl = null,
    overview = "Everything in ${section.title}", metaLine = "", openTarget = "see-all:${section.key}",
)

internal fun seeAllKey(card: CardModel): String? = card.id.takeIf { it.startsWith("see-all:") }?.removePrefix("see-all:")

/**
 * The profile's failed requests that delivered nothing, each with Remove, on servers that take removals
 * (`remove_failed_supported`). Nothing shows otherwise.
 */
@Composable
fun FailedRequests(koin: Koin) {
    val vm = rememberEntryViewModel { MyRequestsViewModel(koin.get(), featureStore = koin.get<RequestsFeatureStore>()) }
    val state by vm.uiState.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.notice) { state.notice?.let { notice = it.message.text.text() } }
    val failed = state.requests.filter { it.isRemovableFailure && state.removeFailedSupported }
    if (failed.isEmpty() && notice == null && state.actionErrorMessage == null) return
    Column(Modifier.padding(start = 64.dp, end = 64.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (failed.isNotEmpty()) {
            Text("Failed requests", color = Silage.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("These couldn't be added. Remove one to clear it, or request it again later.", color = Silage.Text2, fontSize = 17.sp)
        }
        failed.forEach { record ->
            val reason = (RequestDisplayState.of(record) as? RequestDisplayState.NeedsAttention)?.reason?.let { requestReasonText(it)?.text() ?: it }
            Row(
                Modifier.widthIn(max = 1100.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull(record.title, record.year?.toString()).joinToString(" · "), color = Silage.Text, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    reason?.let { Text(it, color = Silage.Text3, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                Pill(if (state.removingId == record.id) "Removing…" else "Remove") { notice = null; vm.removeFailed(record) }
            }
        }
        (state.actionErrorMessage?.text?.text() ?: notice)?.let { Text(it, color = Silage.Amber, fontSize = 17.sp) }
    }
}

/** A Discover row's See all page: every title in it, paged as you scroll. */
@Composable
fun RequestRowScreen(koin: Koin, key: String, title: String, onOpenLibrary: (String) -> Unit, onOpenRequest: (String, Int) -> Unit) {
    val vm = rememberEntryViewModel { RequestDiscoverRowViewModel(koin.get(), key, title) }
    val state by vm.uiState.collectAsState()
    val cards = remember(state.results) { state.results.map { it.toRequestCard() } }
    var focused by remember { mutableStateOf<CardModel?>(null) }
    val first = rememberInitialFocus(cards.isNotEmpty())
    val grid = PagerState(
        items = cards,
        loading = state.isLoading || state.isLoadingMore,
        endReached = state.endReached || state.notFound,
        error = when {
            state.notFound -> "This row is no longer on the server."
            state.error != null -> state.error!!.text()
            state.moreFailed -> "Couldn't load more."
            else -> null
        },
    )
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        PosterGrid(grid, onLoadMore = { vm.loadMore() }, onRetry = { vm.loadMore() },
            onOpen = { openRequestCard(it, onOpenLibrary, onOpenRequest) }, onFocus = { focused = it },
            emptyText = "Nothing in this row right now", firstFocus = first,
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(title, color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 56.dp, bottom = 12.dp))
                }
            })
    }
}
