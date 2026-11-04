package dev.silage.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.Pill
import dev.silage.app.server.SiloExtras
import dev.silage.app.server.TasteSeedItem
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.isActive
import dev.silage.app.ui.rememberInitialFocus
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.model.recommendation.TasteProfile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.RecommendationRepository

/** How many titles the picker wants, as in Silo's web app. */
internal const val TastePicksNeeded = 3

/** "Drama, Thriller and Comedy". */
internal fun tasteList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** Your taste, above For You's rows: top genres and directors, and the way to the picker. */
@Composable
internal fun TasteHeader(koin: Koin, onPick: () -> Unit) {
    var taste by remember { mutableStateOf<TasteProfile?>(null) }
    LaunchedEffect(Unit) {
        val repository = koin.get<RecommendationRepository>()
        val owner = koin.get<org.siloserver.silo.network.TokenManager>().snapshotCurrentScope() ?: return@LaunchedEffect
        (runCatching { repository.getTasteProfile(owner) }.getOrNull() as? ApiResult.Success)?.let { taste = it.data }
    }
    Column(Modifier.padding(start = 64.dp, end = 64.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        taste?.topGenres?.take(4)?.takeIf { it.isNotEmpty() }?.let { Text("You like ${tasteList(it)}", color = Silage.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
        taste?.favoriteDirectors?.take(3)?.takeIf { it.isNotEmpty() }?.let { Text("Favorite directors: ${tasteList(it)}", color = Silage.Text2, fontSize = 17.sp) }
        Pill("Pick titles you like", onClick = onPick)
    }
}

/** Choose at least three titles: each becomes a favorite and starts For You's suggestions. */
@Composable
fun TastePickerScreen(koin: Koin, onDone: () -> Unit) {
    val extras = remember { koin.get<SiloExtras>() }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<TasteSeedItem>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var saving by remember { mutableStateOf(false) }

    suspend fun loadMore() {
        if (loading || finished) return
        loading = true
        extras.tasteSeedItems(cursor).onSuccess { page ->
            val seen = items.mapTo(HashSet()) { it.id }
            // Only titles with posters: there's nothing else to choose by.
            items = items + page.items.filter { it.posterUrl != null && seen.add(it.id) }
            cursor = page.next
            finished = page.next == null
            problem = null
        }.onFailure { problem = "Couldn't load titles: ${it.message}" }
        loading = false
    }
    LaunchedEffect(Unit) { loadMore() }
    val grid = rememberLazyGridState()
    LaunchedEffect(grid, items.size, finished) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (!finished && last >= items.size - 14) loadMore() }
    }
    val first = rememberInitialFocus(items.isNotEmpty())
    LazyVerticalGrid(
        GridCells.Adaptive(170.dp), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 56.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Text("Pick titles you like", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                val left = TastePicksNeeded - picked.size
                Text(if (left > 0) "${picked.size} selected. Choose $left more. They'll be added to your favorites." else "${picked.size} selected. They'll be added to your favorites.", color = Silage.Text2, fontSize = 19.sp)
                problem?.let { Text(it, color = Silage.Error, fontSize = 17.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (left <= 0) Pill(if (saving) "Saving…" else "Done", selected = true) {
                        if (!saving) {
                            saving = true
                            scope.launch {
                                extras.submitTasteSeed(picked.toList()).onSuccess { onDone() }.onFailure { problem = "Couldn't save those: ${it.message}" }
                                saving = false
                            }
                        }
                    }
                    Pill("Cancel", onClick = onDone)
                }
            }
        }
        items(items, key = { it.id }) { item ->
            val on = item.id in picked
            val interaction = remember { MutableInteractionSource() }
            val active = interaction.isActive()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    (if (item.id == items.first().id) Modifier.focusRequester(first) else Modifier)
                        .aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                        .border(3.dp, if (active) Color.White else if (on) Silage.Amber else Color.Transparent, RoundedCornerShape(10.dp))
                        .hoverable(interaction).clickable(interaction, indication = null) { picked = if (on) picked - item.id else picked + item.id },
                ) {
                    Artwork(item.posterUrl, item.posterThumbhash, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (on) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Silage.Amber), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, "Selected", tint = Color.Black, modifier = Modifier.size(20.dp))
                    }
                }
                Text(item.title, color = if (active) Color.White else Silage.Text2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Silage.Text, modifier = Modifier.size(40.dp)) }
        }
        if (finished && items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Nothing to choose from yet. Titles appear here once your library is matched.", color = Silage.Text2, fontSize = 20.sp)
        }
    }
}
