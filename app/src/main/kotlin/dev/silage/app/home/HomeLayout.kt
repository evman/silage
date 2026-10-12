package dev.silage.app.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/** Where Home's rows begin at rest, below the full banner (about 37% of the 1080 layout height). */
val HomeRowsTop = 400.dp

/** Where they begin once scrolling has started: the banner shrinks to half. */
val HomeRowsTopCollapsed = 200.dp

/** The banner collapses as soon as the rows are scrolled at all. */
fun homeCollapsed(firstVisibleIndex: Int, firstVisibleOffset: Int): Boolean = firstVisibleIndex > 0 || firstVisibleOffset > 0

/**
 * Home: the banner owns the top of the screen and the rows scroll in their
 * own viewport below it (clipped there), so they never run over its text.
 * Once scrolling begins the banner shrinks to half its height with an
 * ease-out, and grows back when the rows return to the top. [marquee] gets
 * the collapse progress, 0 (full) to 1 (compact).
 */
@Composable
fun HomeLayout(
    marquee: @Composable BoxScope.(collapse: Float) -> Unit,
    listState: LazyListState,
    onBannerBottom: (Float) -> Unit = {},
    rows: LazyListScope.() -> Unit,
) {
    val collapsed by remember(listState) { derivedStateOf { homeCollapsed(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) } }
    val collapse by animateFloatAsState(if (collapsed) 1f else 0f, tween(400, easing = FastOutSlowInEasing), label = "banner")
    val top = lerp(HomeRowsTop, HomeRowsTopCollapsed, collapse)
    val topPx = with(LocalDensity.current) { top.toPx() }
    SideEffect { onBannerBottom(topPx) }
    Box(Modifier.fillMaxSize()) {
        marquee(collapse)
        LazyColumn(
            Modifier.fillMaxSize().padding(top = top),
            state = listState,
            // Room for a focused card's ring and scale at the top edge.
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
            content = rows,
        )
    }
}
