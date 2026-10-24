package dev.silage.app.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.blur
import dev.silage.app.settings.LocalProfileUi
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.ArrowRow
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.focusCard
import dev.silage.app.ui.isActive

private val CardShape = RoundedCornerShape(10.dp)

/** A poster (2:3) or landscape (16:9) card with its title underneath. */
@Composable
fun MediaCard(
    card: CardModel,
    width: Dp,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    LaunchedEffect(active) { if (active) onFocus() }
    val height = if (card.landscape) width * 9f / 16f else width * 1.5f
    // Right-click: Play / Open / Watchlist / Watched, where the page offers it.
    val menu = LocalCardMenu.current
    if (menu != null && card.kind != null) {
        androidx.compose.foundation.ContextMenuArea(items = { menu.items(card) }) { CardBody(card, width, height, active, interaction, onOpen, modifier, focusRequester, menu) }
    } else {
        CardBody(card, width, height, active, interaction, onOpen, modifier, focusRequester, null)
    }
}

@Composable
private fun CardBody(card: CardModel, width: Dp, height: Dp, active: Boolean, interaction: MutableInteractionSource, onOpen: () -> Unit,
    modifier: Modifier, focusRequester: FocusRequester?, menu: CardMenu?) {
    val ui = LocalProfileUi.current
    val scope = rememberCoroutineScope()
    // The shortcut buttons show their change at once; the page reloads later.
    var watched by remember(card.id, card.watched) { mutableStateOf(card.watched) }
    var favorite by remember(card.id, card.favorite) { mutableStateOf(card.favorite) }
    val quick = ui.quickActionsEnabled && menu != null && card.kind in setOf("movie", "series", "show", "episode", "season")
    val blur = card.hidesImage(ui.spoilers)
    Column(modifier.width(width)) {
        Box(
            Modifier.size(width, height)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusCard(active, CardShape)
                .clip(CardShape)
                .hoverable(interaction)
                .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                .clickable(interaction, indication = null, onClick = onOpen),
        ) {
            Artwork(card.imageUrl, card.thumbhash, card.title, Modifier.fillMaxSize().then(if (blur) Modifier.blur(28.dp) else Modifier))
            BadgeLayer(card, ui, watchedMarkShown = watched, hasProgressBar = card.progress != null)
            card.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Silage.Amber))
                }
            }
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = Silage.Amber, modifier = Modifier.size(18.dp)) }
            }
            if (quick && active) {
                QuickActionRow(
                    ui.quickActions, favorite, watched,
                    onFavorite = { val to = !favorite; favorite = to; scope.launch { if (!menu!!.quickFavorite(card.id, to)) favorite = !to } },
                    onWatched = { val to = !watched; watched = to; scope.launch { if (!menu!!.quickWatched(card.id, to)) watched = !to } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(start = 8.dp, end = 8.dp, bottom = if (card.progress != null) 14.dp else 8.dp),
                )
            }
        }
        // Under each poster: the title and details, or nothing for artwork only.
        val caption = ui.presentation.caption
        if (caption.showsTitle) {
            Spacer(Modifier.height(10.dp))
            Text(card.title, color = if (active) Color.White else Silage.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption.showsMetadata) card.caption?.let { Text(it, color = Silage.Text3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A titled horizontal row of cards. */
@Composable
fun CardRow(
    title: String,
    cards: List<CardModel>,
    onOpen: (CardModel) -> Unit,
    onFocus: (CardModel) -> Unit,
    firstCardFocus: FocusRequester? = null,
    /** The card [firstCardFocus] attaches to; the first card when null. */
    focusCardId: String? = null,
) {
    val landscape = cards.firstOrNull()?.landscape == true
    val scale = LocalProfileUi.current.presentation.posterSize.posterScale
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Silage.Text2, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 64.dp, bottom = 14.dp))
        // ‹ › buttons for the mouse; centred on the artwork rather than artwork plus caption.
        ArrowRow(contentPadding = PaddingValues(horizontal = 64.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), arrowOffsetY = 26.dp) {
            itemsIndexed(cards, key = { _, c -> c.id }) { i, card ->
                MediaCard(
                    card, width = (if (landscape) 400.dp else 200.dp) * scale,
                    onOpen = { onOpen(card) }, onFocus = { onFocus(card) },
                    focusRequester = if (if (focusCardId != null) card.id == focusCardId else i == 0) firstCardFocus else null,
                )
            }
        }
    }
}
