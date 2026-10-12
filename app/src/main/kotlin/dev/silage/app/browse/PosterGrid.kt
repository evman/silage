package dev.silage.app.browse

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Artwork
import dev.silage.app.ui.Silage
import dev.silage.app.ui.isActive

/** A pill button: inverts to white on focus/hover; [selected] gets a white outline. */
@Composable
fun Pill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val active = interaction.isActive()
    Box(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.08f))
            .then(if (selected && !active) Modifier.border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(24.dp)) else Modifier)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (active) Color.Black else Silage.Text, fontSize = 18.sp) }
}

/** The focused item's backdrop, faint behind a page. */
@Composable
fun PageBackdrop(card: CardModel?) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(card?.backdropUrl, animationSpec = tween(400), modifier = Modifier.align(Alignment.TopEnd)) { url ->
            Artwork(url, null, "", Modifier.fillMaxWidth(0.75f).fillMaxHeight(0.7f), contentScale = ContentScale.Crop, showTitleTile = false)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.35f to Color.Black, 1f to Color.Black.copy(alpha = 0.55f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.2f to Color.Black.copy(alpha = 0.3f), 0.7f to Color.Black)))
    }
}

/**
 * A poster grid over a [PagerState]: loads more as the end comes into view,
 * shows an empty message, and keeps items with a retry row when a page fails.
 */
@Composable
fun PosterGrid(
    state: PagerState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (CardModel) -> Unit,
    onFocus: (CardModel) -> Unit,
    emptyText: String = "Nothing here yet",
    /** Attached to the card with [restoreId] (the last one focused), else the first card. */
    firstFocus: FocusRequester? = null,
    restoreId: String? = null,
    header: LazyGridScope.() -> Unit = {},
) {
    val grid = rememberLazyGridState()
    val items = state.items
    LaunchedEffect(grid, items.size, state.endReached) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (!state.endReached && !state.loading && state.error == null && last >= items.size - 14) onLoadMore()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        state = grid,
        contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 12.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        header()
        val target = restoreId?.takeIf { id -> items.any { it.id == id } } ?: items.firstOrNull()?.id
        itemsIndexed(items, key = { _, c -> c.id }) { _, card ->
            MediaCard(card, 200.dp, onOpen = { onOpen(card) }, onFocus = { onFocus(card) }, focusRequester = if (card.id == target) firstFocus else null)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                when {
                    state.error != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(if (items.isEmpty()) state.error else "Couldn't load more", color = Silage.Text2, fontSize = 20.sp)
                        Pill("Retry", onClick = onRetry)
                    }
                    state.loading || (!state.endReached && items.isEmpty()) -> CircularProgressIndicator(color = Silage.Text, modifier = Modifier.size(40.dp))
                    state.endReached && items.isEmpty() -> Text(emptyText, color = Silage.Text2, fontSize = 22.sp)
                }
            }
        }
    }
}
