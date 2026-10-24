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
import dev.silage.app.browse.hidesImage
import dev.silage.app.browse.hidesOverview
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
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
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.koin.core.Koin
import org.siloserver.silo.viewmodel.HomeViewModel

/** Home: a marquee for the focused card over the server's rows. */
@Composable
fun HomeScreen(koin: Koin, onOpen: (CardModel) -> Unit, onPlay: (CardModel) -> Unit) {
    // Owned by this visit's back-stack entry (cleared when it's popped); it loads on creation.
    val vm = rememberEntryViewModel { koin.get<HomeViewModel>() }
    val state by vm.uiState.collectAsState()
    // Home's right-click menu can also remove a card from Continue Watching / Next Up.
    val base = dev.silage.app.browse.LocalCardMenu.current
    val sections = remember { koin.get<org.siloserver.silo.repository.SectionRepository>() }
    // Shows the server accepted a removal for: hidden from the progress rows at once, whether or not
    // the reload that follows is served stale.
    var removed by remember { mutableStateOf(emptySet<String>()) }
    val menu = remember(base, vm) {
        base?.forHome(
            // Straight to the server, so its answer can be shown. It drops the whole show from both
            // rows, which is why Home reloads afterwards.
            dismiss = { card ->
                val target = dev.silage.app.browse.homeDismissal(card)
                val owner = sections.captureHomeAuthority()
                when {
                    target == null -> "The server didn't say which entry this is."
                    owner == null -> "You're signed out."
                    else -> when (val r = sections.dismissHomeItem(target.surface, card.id, target.anchor, owner)) {
                        is org.siloserver.silo.network.ApiResult.Success -> { removed = removed + (card.seriesId ?: card.id); null }
                        is org.siloserver.silo.network.ApiResult.Error -> "Couldn't remove it: ${r.message}"
                        else -> "Can't reach the server."
                    }
                }
            },
            changed = { vm.refresh() },
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(dev.silage.app.browse.LocalCardMenu provides menu) {
        val shown = remember(state.sections, removed) { withoutRemovedShows(state.sections, removed) }
        SectionRowsScreen(shown, state.isLoading, state.error, onRetry = { vm.refresh() }, emptyText = "Nothing on Home yet", onOpen = onOpen, onPlay = onPlay)
    }
}

/** The progress rows (Continue Watching, Next Up) without the shows in [removed] (series ids, or the item's own id). */
internal fun withoutRemovedShows(
    sections: List<org.siloserver.silo.model.section.ResolvedSection>, removed: Set<String>,
): List<org.siloserver.silo.model.section.ResolvedSection> =
    if (removed.isEmpty()) sections else sections.map { section ->
        if (!isProgressRow(section.sectionType)) section
        else section.copy(items = section.items.filterNot { (it.seriesId ?: it.contentId) in removed })
    }

/** For You: the server's personal recommendation rows, laid out like Home. */
@Composable
fun ForYouScreen(koin: Koin, onOpen: (CardModel) -> Unit, onPlay: (CardModel) -> Unit) {
    val vm = rememberEntryViewModel { org.siloserver.silo.viewmodel.RecommendationsViewModel(koin.get()) }
    val state by vm.uiState.collectAsState()
    SectionRowsScreen(state.sections, state.isLoading, state.error, onRetry = { vm.refresh() },
        emptyText = "No recommendations yet. Watch a few things and check back.", onOpen = onOpen, onPlay = onPlay)
}

/** A marquee for the focused card over rows of cards (Home, For You). */
@Composable
fun SectionRowsScreen(
    sections: List<org.siloserver.silo.model.section.ResolvedSection>,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    emptyText: String,
    onOpen: (CardModel) -> Unit,
    onPlay: (CardModel) -> Unit,
) {
    val rows = remember(sections) {
        sections.filter { it.items.isNotEmpty() }.map { section ->
            val progress = isProgressRow(section.sectionType)
            Triple(section, progress, section.items.map { it.toCard(landscape = progress).copy(homeRow = section.sectionType) })
        }
    }
    // The focused card. Only the marquee reads it, so moving between cards
    // (keys or mouse hover) doesn't recompose Home and every visible row.
    val focused = remember { mutableStateOf<CardModel?>(null) }
    // "rowId/cardId" of the last focused card, kept across a visit to a detail page.
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    // Read without subscribing: it only matters when Home is (re)entered or its rows reload.
    val savedFocus = remember(rows) { Snapshot.withoutReadObservation { lastFocused } }
    val restoreRow = savedFocus?.substringBefore('/')?.takeIf { r -> rows.any { it.first.id == r } }
    val restoreCard = if (restoreRow != null) savedFocus?.substringAfter('/') else null
    val marquee = rows.firstOrNull { it.first.id == restoreRow }?.third?.firstOrNull { it.id == restoreCard }
        ?: rows.firstOrNull()?.third?.firstOrNull()
    val firstCard = rememberInitialFocus(rows.isNotEmpty())

    val listState = rememberLazyListState()
    // Developer switch for screenshots: start scrolled down N rows.
    LaunchedEffect(rows.isNotEmpty()) { System.getenv("SILAGE_DEV_HOME_SCROLL")?.toIntOrNull()?.let { if (rows.size > it) listState.scrollToItem(it) } }
    Box(Modifier.fillMaxSize().background(Silage.Black)) {
        when {
            rows.isEmpty() && isLoading -> { Marquee(marquee); Skeleton() }
            rows.isEmpty() && error != null -> { Marquee(marquee); ErrorState(error, onRetry) }
            rows.isEmpty() -> { Marquee(marquee); Text(emptyText, color = Silage.Text2, fontSize = 24.sp, modifier = Modifier.align(Alignment.Center)) }
            else -> HomeLayout(marquee = { collapse -> FocusedMarquee(focused, marquee, collapse) }, listState = listState) {
                items(rows, key = { it.first.id }) { (section, progress, cards) ->
                    CardRow(
                        section.title, cards,
                        onOpen = { if (progress) onPlay(it) else onOpen(it) },
                        onFocus = { focused.value = it; lastFocused = "${section.id}/${it.id}" },
                        firstCardFocus = if (section.id == (restoreRow ?: rows.first().first.id)) firstCard else null,
                        focusCardId = if (section.id == restoreRow) restoreCard else null,
                    )
                }
            }
        }
    }
}

/** How long focus must rest on a card before the banner switches to it. */
internal const val MarqueeSettleMs = 150L

/**
 * The banner, following [focused] (or [fallback] until something is focused).
 * The first focus shows at once; later moves wait [MarqueeSettleMs] so holding
 * an arrow key doesn't start a backdrop load and crossfade for every card passed.
 */
@Composable
private fun FocusedMarquee(focused: State<CardModel?>, fallback: CardModel?, collapse: Float) {
    var shown by remember { mutableStateOf<CardModel?>(null) }
    LaunchedEffect(focused) {
        snapshotFlow { focused.value }.collectLatest { card ->
            if (card == null) return@collectLatest
            if (shown != null) delay(MarqueeSettleMs)
            shown = card
        }
    }
    Marquee(shown ?: fallback, collapse)
}

/**
 * The banner for the focused card. [collapse] runs 0 (full, at the top of
 * Home) to 1 (compact, once the rows are scrolled): the logo and title shrink
 * and the description fades and folds away, leaving title and details.
 */
@Composable
private fun Marquee(card: CardModel?, collapse: Float = 0f) {
    fun l(a: Float, b: Float) = a + (b - a) * collapse
    val spoilers = dev.silage.app.settings.LocalProfileUi.current.spoilers
    val hideBackdrop = card?.hidesImage(spoilers) == true
    Box(Modifier.fillMaxWidth().height(l(560f, 300f).dp)) {
        Crossfade(card?.backdropUrl to card?.backdropThumbhash, animationSpec = tween(350), modifier = Modifier.align(Alignment.TopEnd)) { (url, hash) ->
            Artwork(url, hash, "", Modifier.fillMaxWidth(0.78f).fillMaxHeight().then(if (hideBackdrop) Modifier.blur(40.dp) else Modifier), contentScale = ContentScale.Crop, showTitleTile = false)
        }
        // Fade the backdrop into black on the left and bottom so text and rows stay legible.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.30f to Color.Black, 0.62f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black)))
        if (card != null) {
            Column(Modifier.padding(start = 64.dp, top = l(52f, 26f).dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(l(10f, 6f).dp)) {
                if (card.logoUrl != null) {
                    Artwork(card.logoUrl, null, card.title, Modifier.height(l(104f, 58f).dp).widthIn(max = l(420f, 240f).dp).fillMaxWidth(), contentScale = ContentScale.Fit, showTitleTile = false, alignment = Alignment.CenterStart)
                } else {
                    Text(card.title, color = Color.White, fontSize = l(42f, 30f).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val meta = listOfNotNull(card.caption?.takeIf { card.landscape }, card.metaLine.ifBlank { null }).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, color = Silage.Text2, fontSize = l(18f, 16f).sp)
                card.overview?.takeIf { !card.hidesOverview(spoilers) }?.let {
                    Text(
                        it, color = Silage.Text, fontSize = 17.sp, lineHeight = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.foldAway(collapse),
                    )
                }
            }
        }
    }
}

/** Fades out and folds to zero height as [progress] goes 0 → 1 (fading faster than it folds). */
private fun Modifier.foldAway(progress: Float): Modifier = this
    .graphicsLayer { alpha = (1f - progress * 1.8f).coerceIn(0f, 1f) }
    .clipToBounds()
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val h = (p.height * (1f - progress)).roundToInt()
        layout(p.width, h) { p.place(0, 0) }
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
