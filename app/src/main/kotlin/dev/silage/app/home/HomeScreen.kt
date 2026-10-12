package dev.silage.app.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.CardModel
import dev.silage.app.browse.CardRow
import dev.silage.app.browse.isProgressRow
import dev.silage.app.browse.toCard
import dev.silage.app.ui.Artwork
import dev.silage.app.nav.rememberEntryViewModel
import dev.silage.app.ui.Silage
import dev.silage.app.ui.rememberInitialFocus
import androidx.compose.runtime.saveable.rememberSaveable
import org.koin.core.Koin
import org.siloserver.silo.viewmodel.HomeViewModel

/** Home: a marquee for the focused card over the server's rows. */
@Composable
fun HomeScreen(koin: Koin, onOpen: (CardModel) -> Unit, onPlay: (CardModel) -> Unit) {
    // Owned by this visit's back-stack entry (cleared when it's popped); it loads on creation.
    val vm = rememberEntryViewModel { koin.get<HomeViewModel>() }
    val state by vm.uiState.collectAsState()

    val rows = remember(state.sections) {
        state.sections.filter { it.items.isNotEmpty() }.map { section ->
            val progress = isProgressRow(section.sectionType)
            Triple(section, progress, section.items.map { it.toCard(landscape = progress) })
        }
    }
    var focused by remember { mutableStateOf<CardModel?>(null) }
    // "rowId/cardId" of the last focused card, kept across a visit to a detail page.
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val restoreRow = lastFocused?.substringBefore('/')?.takeIf { r -> rows.any { it.first.id == r } }
    val restoreCard = if (restoreRow != null) lastFocused?.substringAfter('/') else null
    val marquee = focused
        ?: rows.firstOrNull { it.first.id == restoreRow }?.third?.firstOrNull { it.id == restoreCard }
        ?: rows.firstOrNull()?.third?.firstOrNull()
    val firstCard = rememberInitialFocus(rows.isNotEmpty())

    val listState = rememberLazyListState()
    // Developer switch for screenshots: start scrolled down N rows.
    LaunchedEffect(rows.isNotEmpty()) { System.getenv("SILAGE_DEV_HOME_SCROLL")?.toIntOrNull()?.let { if (rows.size > it) listState.scrollToItem(it) } }
    Box(Modifier.fillMaxSize().background(Silage.Black)) {
        when {
            rows.isEmpty() && state.isLoading -> { Marquee(marquee); Skeleton() }
            rows.isEmpty() && state.error != null -> { Marquee(marquee); ErrorState(state.error!!) { vm.refresh() } }
            rows.isEmpty() -> { Marquee(marquee); Text("Nothing on Home yet", color = Silage.Text2, fontSize = 24.sp, modifier = Modifier.align(Alignment.Center)) }
            else -> HomeLayout(marquee = { Marquee(marquee) }, listState = listState) {
                items(rows, key = { it.first.id }) { (section, progress, cards) ->
                    CardRow(
                        section.title, cards,
                        onOpen = { if (progress) onPlay(it) else onOpen(it) },
                        onFocus = { focused = it; lastFocused = "${section.id}/${it.id}" },
                        firstCardFocus = if (section.id == (restoreRow ?: rows.first().first.id)) firstCard else null,
                        focusCardId = if (section.id == restoreRow) restoreCard else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun Marquee(card: CardModel?) {
    Box(Modifier.fillMaxWidth().height(760.dp)) {
        Crossfade(card?.backdropUrl to card?.backdropThumbhash, animationSpec = tween(350), modifier = Modifier.align(Alignment.TopEnd)) { (url, hash) ->
            Artwork(url, hash, "", Modifier.fillMaxWidth(0.78f).fillMaxHeight(), contentScale = ContentScale.Crop, showTitleTile = false)
        }
        // Fade the backdrop into black on the left and bottom so text and rows stay legible.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.30f to Color.Black, 0.62f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black)))
        if (card != null) {
            Column(Modifier.padding(start = 64.dp, top = 96.dp).widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (card.logoUrl != null) {
                    Artwork(card.logoUrl, null, card.title, Modifier.height(150.dp).widthIn(max = 560.dp).fillMaxWidth(), contentScale = ContentScale.Fit, showTitleTile = false, alignment = Alignment.CenterStart)
                } else {
                    Text(card.title, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 60.sp)
                }
                val meta = listOfNotNull(card.caption?.takeIf { card.landscape }, card.metaLine.ifBlank { null }).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, color = Silage.Text2, fontSize = 20.sp)
                card.overview?.let { Text(it, color = Silage.Text, fontSize = 19.sp, lineHeight = 28.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun Skeleton() {
    Column(Modifier.fillMaxSize().padding(start = 64.dp, top = 600.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                repeat(7) { Box(Modifier.size(200.dp, 300.dp).clip(RoundedCornerShape(10.dp)).background(Silage.Elevated)) }
            }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = Silage.Text, fontSize = 24.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.focusRequester(retry), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Text("Retry", fontSize = 18.sp)
        }
    }
}
