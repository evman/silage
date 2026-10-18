package dev.silage.app.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.CardModel
import dev.silage.app.browse.CardRow
import dev.silage.app.browse.PageBackdrop
import dev.silage.app.browse.formatRuntime
import dev.silage.app.detail.ActionButton
import dev.silage.app.home.ErrorState
import dev.silage.app.nav.rememberEntryViewModel
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.rememberInitialFocus
import org.koin.core.Koin
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestStatusTint
import org.siloserver.silo.model.request.RequestStep
import org.siloserver.silo.model.request.requestBackdropUrl
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.viewmodel.RequestDetailViewModel
import org.siloserver.silo.viewmodel.RequestPrimaryAction
import org.siloserver.silo.viewmodel.RequestsViewModel

internal fun RequestStatusTint.color(): Color = when (this) {
    RequestStatusTint.Amber -> Silage.Amber
    RequestStatusTint.Sky -> Color(0xFF7CC4F2)
    RequestStatusTint.Emerald -> Color(0xFF6FD3A0)
    RequestStatusTint.Rose -> Color(0xFFF08A8A)
    RequestStatusTint.Neutral -> Silage.Text2
}

/** "2024 · 2h 15m · Drama, Thriller · PG-13", or the series equivalent. */
internal fun RequestMediaDetail.metaLine(): String = listOfNotNull(
    year?.toString(),
    if (mediaType == RequestMediaType.Series) numberOfSeasons?.let { if (it == 1) "1 season" else "$it seasons" } else formatRuntime(runtime),
    genres.take(3).joinToString(", ").takeIf { it.isNotBlank() },
    contentRating.takeIf { it.isNotBlank() },
).joinToString(" · ")

/** Opens what a request card points at. */
fun openRequestCard(card: CardModel, onOpenLibrary: (String) -> Unit, onOpenRequest: (String, Int) -> Unit) {
    when (val target = requestTargetOf(card)) {
        is RequestTarget.Library -> onOpenLibrary(target.contentId)
        is RequestTarget.Request -> onOpenRequest(target.mediaType, target.tmdbId)
        null -> onOpenLibrary(card.openTarget)
    }
}

/** A title that isn't in the library: what it is, its request status, and the one button to request it. */
@Composable
fun RequestDetailScreen(koin: Koin, mediaType: String, tmdbId: Int, onOpenLibrary: (String) -> Unit, onOpenRequest: (String, Int) -> Unit) {
    val vm = rememberEntryViewModel { RequestDetailViewModel(koin.get(), mediaType, tmdbId, koin.get<RequestsFeatureStore>()) }
    val state by vm.uiState.collectAsState()
    val detail = state.detail
    when {
        detail == null && state.error != null -> ErrorState(state.error!!) { vm.load() }
        detail == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Silage.Text) }
        else -> {
            val primaryFocus = rememberInitialFocus(true)
            Box(Modifier.fillMaxSize().background(Silage.Black)) {
                Artwork(requestBackdropUrl(detail.backdropPath), null, "", Modifier.fillMaxWidth().fillMaxHeight(0.85f).align(Alignment.TopEnd), showTitleTile = false)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.25f to Color.Black.copy(alpha = 0.92f), 0.65f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.82f to Color.Black)))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    item {
                        Row(Modifier.padding(start = 64.dp, top = 120.dp, end = 64.dp)) {
                            Artwork(requestPosterUrl(detail.posterPath), null, detail.title, Modifier.width(240.dp).height(360.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)))
                            Column(Modifier.padding(start = 48.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(detail.title, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold, lineHeight = 60.sp)
                                detail.metaLine().takeIf { it.isNotBlank() }?.let { Text(it, color = Silage.Text2, fontSize = 20.sp) }
                                detail.tagline.takeIf { it.isNotBlank() }?.let { Text(it, color = Silage.Text, fontSize = 20.sp, fontStyle = FontStyle.Italic) }
                                detail.overview.takeIf { it.isNotBlank() }?.let { Text(it, color = Silage.Text, fontSize = 19.sp, lineHeight = 29.sp, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                                credits(detail)?.let { Text(it, color = Silage.Text2, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                state.progress?.let { StageTrack(it) }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    PrimaryAction(state.primaryAction, Modifier.focusRequester(primaryFocus), onRequest = vm::submitRequest, onOpen = onOpenLibrary)
                                    if (state.canCancel) ActionButton(Icons.Filled.Close, "Cancel request") { vm.cancel() }
                                }
                                state.actionErrorMessage?.let { Text(it, color = RequestStatusTint.Rose.color(), fontSize = 17.sp) }
                            }
                        }
                    }
                    if (detail.recommendations.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(56.dp))
                            CardRow("More like this", detail.recommendations.map { it.toRequestCard() },
                                onOpen = { openRequestCard(it, onOpenLibrary, onOpenRequest) }, onFocus = {})
                        }
                    }
                }
            }
        }
    }
}

private fun credits(detail: RequestMediaDetail): String? = listOfNotNull(
    detail.director.takeIf { it.isNotBlank() }?.let { "Directed by $it" },
    detail.creators.takeIf { it.isNotEmpty() }?.let { "Created by ${it.take(2).joinToString(", ")}" },
    detail.cast.sortedBy { it.order }.take(4).map { it.name }.takeIf { it.isNotEmpty() }?.let { "Starring ${it.joinToString(", ")}" },
).joinToString("   ·   ").takeIf { it.isNotBlank() }

@Composable
private fun PrimaryAction(action: RequestPrimaryAction, modifier: Modifier, onRequest: () -> Unit, onOpen: (String) -> Unit) {
    // Always one button in the same place, so focus stays on it as it changes.
    when (action) {
        RequestPrimaryAction.Loading -> ActionButton(Icons.Filled.Schedule, "Checking…", modifier) {}
        RequestPrimaryAction.Request -> ActionButton(Icons.Filled.Add, "Request", modifier, onRequest)
        RequestPrimaryAction.Submitting -> ActionButton(Icons.Filled.Schedule, "Requesting…", modifier) {}
        is RequestPrimaryAction.OpenInLibrary -> ActionButton(Icons.Filled.PlayArrow, "Open in library", modifier) { onOpen(action.contentId) }
        is RequestPrimaryAction.Status -> ActionButton(statusIcon(action.state), action.state.detailTitle, modifier) {}
    }
}

private fun statusIcon(state: RequestDisplayState): ImageVector = when (state) {
    RequestDisplayState.Pending -> Icons.Filled.HourglassTop
    RequestDisplayState.OnTheWay -> Icons.Filled.Schedule
    RequestDisplayState.InLibrary -> Icons.Filled.CheckCircle
    is RequestDisplayState.NeedsAttention, is RequestDisplayState.Unavailable -> Icons.Filled.ErrorOutline
}

/** Requested → Approved → Downloading → In your library, with the current step in the status color. */
@Composable
private fun StageTrack(progress: RequestProgress) {
    val tint = progress.tint.color()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(tint))
            Text(progress.longLabel, color = tint, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RequestStep.entries.forEachIndexed { i, step ->
                val done = i < progress.completedSteps
                val current = step == progress.currentStep
                Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(
                        when { done -> Color.White; current -> tint; else -> Color.White.copy(alpha = 0.15f) }))
                    Text(step.title, color = if (done || current) Silage.Text else Silage.Text3, fontSize = 15.sp)
                }
            }
        }
    }
}

/** The Requests page: the user's own requests, then the server's suggestions of what to request. */
@Composable
fun RequestsScreen(koin: Koin, onOpenLibrary: (String) -> Unit, onOpenRequest: (String, Int) -> Unit, onSearch: () -> Unit) {
    val vm = rememberEntryViewModel { RequestsViewModel(koin.get(), koin.get<RequestsFeatureStore>()) }
    val state by vm.uiState.collectAsState()
    // Loads on creation; coming back to the page re-reads it (a request may have changed).
    LaunchedEffect(vm) { if (state.hasLoaded) vm.refresh() }
    var focused by remember { mutableStateOf<CardModel?>(null) }
    val rows = remember(state.myRequests, state.sections) {
        buildList<Pair<String, Pair<String, List<CardModel>>>> {
            if (state.myRequests.isNotEmpty()) add("mine" to ("Your requests" to state.myRequests.map { it.toRequestCard() }))
            state.sections.filter { it.results.isNotEmpty() }.forEach { add(it.key to (it.title to it.results.map { r -> r.toRequestCard() })) }
        }
    }
    val first = rememberInitialFocus(rows.isNotEmpty())
    var lastRow by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item {
                Row(Modifier.padding(start = 64.dp, top = 56.dp, end = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Requests", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                        Text("Ask for movies and series that aren't on the server yet.", color = Silage.Text2, fontSize = 19.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    dev.silage.app.browse.Pill("Search to request", onClick = onSearch)
                }
                val summary = listOfNotNull(
                    state.pendingCount.takeIf { it > 0 }?.let { "$it pending" },
                    state.onTheWayCount.takeIf { it > 0 }?.let { "$it on the way" },
                    state.needsAttentionCount.takeIf { it > 0 }?.let { "$it need attention" },
                ).joinToString(" · ")
                if (summary.isNotBlank()) Text(summary, color = Silage.Amber, fontSize = 18.sp, modifier = Modifier.padding(start = 64.dp, top = 12.dp))
            }
            when {
                rows.isEmpty() && state.isLoading -> item {
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Silage.Text) }
                }
                rows.isEmpty() && state.error != null -> item {
                    Row(Modifier.padding(start = 64.dp, top = 40.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error!!, color = Silage.Text2, fontSize = 20.sp)
                        dev.silage.app.browse.Pill("Retry") { vm.load() }
                    }
                }
                rows.isEmpty() -> item {
                    Text("Nothing to show yet. Search for a title to request it.", color = Silage.Text2, fontSize = 22.sp, modifier = Modifier.padding(start = 64.dp, top = 40.dp))
                }
                else -> items(rows, key = { it.first }) { (key, row) ->
                    CardRow(row.first, row.second,
                        onOpen = { lastRow = key; openRequestCard(it, onOpenLibrary, onOpenRequest) },
                        onFocus = { focused = it },
                        firstCardFocus = if (key == (lastRow?.takeIf { r -> rows.any { it.first == r } } ?: rows.first().first)) first else null)
                }
            }
        }
    }
}
