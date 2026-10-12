package dev.silage.app.home

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Where Home's rows begin, below the marquee. */
val HomeRowsTop = 560.dp

/**
 * Home: the marquee owns the top of the screen; the rows scroll in their own
 * viewport below it (clipped there), so scrolling never runs them over the
 * marquee's title and description.
 */
@Composable
fun HomeLayout(marquee: @Composable BoxScope.() -> Unit, listState: LazyListState, rows: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        marquee()
        LazyColumn(
            Modifier.fillMaxSize().padding(top = HomeRowsTop),
            state = listState,
            // Room for a focused card's ring and scale at the top edge.
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
            content = rows,
        )
    }
}
