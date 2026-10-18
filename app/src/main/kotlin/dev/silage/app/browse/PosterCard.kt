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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
    Column(modifier.width(width)) {
        Box(
            Modifier.size(width, height)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusCard(active, CardShape)
                .clip(CardShape)
                .hoverable(interaction)
                .clickable(interaction, indication = null, onClick = onOpen),
        ) {
            Artwork(card.imageUrl, card.thumbhash, card.title, Modifier.fillMaxSize())
            card.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Silage.Amber))
                }
            }
            if (card.watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = Silage.Amber, modifier = Modifier.size(18.dp)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(card.title, color = if (active) Color.White else Silage.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        card.caption?.let { Text(it, color = Silage.Text3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Silage.Text2, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 64.dp, bottom = 14.dp))
        // ‹ › buttons for the mouse; centred on the artwork rather than artwork plus caption.
        ArrowRow(contentPadding = PaddingValues(horizontal = 64.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), arrowOffsetY = 26.dp) {
            itemsIndexed(cards, key = { _, c -> c.id }) { i, card ->
                MediaCard(
                    card, width = if (landscape) 400.dp else 200.dp,
                    onOpen = { onOpen(card) }, onFocus = { onFocus(card) },
                    focusRequester = if (if (focusCardId != null) card.id == focusCardId else i == 0) firstCardFocus else null,
                )
            }
        }
    }
}
