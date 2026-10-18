package dev.silage.app.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.nav.rememberEntry
import dev.silage.app.ui.Silage
import dev.silage.app.ui.rememberInitialFocus
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.repository.CatalogRepository

/** A cast or crew member's movies and series in this library, newest first. */
@Composable
fun PersonScreen(koin: Koin, personId: Long, name: String, onOpen: (CardModel) -> Unit) {
    val catalog = remember { koin.get<CatalogRepository>() }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<CardModel?>(null) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val pager = rememberEntry("person:$personId") {
        LibraryPager<CatalogContinuationV2> { c -> catalog.getPersonItems(personId, continuation = c, limit = 60).toPage() }
    }
    val state by pager.state.collectAsState()
    LaunchedEffect(pager) { if (pager.state.value.items.isEmpty()) pager.loadMore() }
    val first = rememberInitialFocus(state.items.isNotEmpty())
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        PosterGrid(state, { scope.launch { pager.loadMore() } }, { scope.launch { pager.retry() } }, onOpen, { focused = it; lastFocused = it.id },
            emptyText = "Nothing with $name in your library", firstFocus = first, restoreId = lastFocused, header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(name, color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 56.dp, bottom = 12.dp))
                }
            })
    }
}
