package dev.silage.app.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import dev.silage.app.ui.ArrowRow
import dev.silage.app.ui.Silage
import dev.silage.app.ui.rememberInitialFocus
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.model.personal.Collection as SiloCollection
import org.siloserver.silo.model.personal.CollectionGroup
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.resolveArtworkUrl
import org.siloserver.silo.repository.CollectionRepository

/** A collection as a poster card that opens the collection. */
fun SiloCollection.toCard(serverUrl: String?): CardModel {
    val poster = posterUrl?.takeIf { it.isNotBlank() }?.let { url ->
        if (url.startsWith("/") && serverUrl != null) runCatching { resolveArtworkUrl(url, serverUrl) }.getOrDefault(url) else url
    }
    return CardModel(id, name, itemCount?.let { if (it == 1) "1 item" else "$it items" }, poster, posterThumbhash, false, null, false,
        null, null, null, description, "", id)
}

/** Collections in the server's order: by group, then each one's place, then name. */
fun orderedCollections(all: List<SiloCollection>, groups: List<CollectionGroup>, groupId: String?): List<SiloCollection> {
    val groupRank = groups.associate { it.id to it.sortOrder }
    return all.filter { groupId == null || it.groupId == groupId }
        .sortedWith(compareBy<SiloCollection>({ groupRank[it.groupId] ?: Int.MAX_VALUE }, { it.sortOrder }, { it.name.lowercase() }))
}

/** Every Silo collection this profile can see, with a pill per collection group. */
@Composable
fun CollectionsScreen(koin: Koin, onOpenCollection: (id: String, name: String) -> Unit) {
    val repository = remember { koin.get<CollectionRepository>() }
    val scope = rememberCoroutineScope()
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<CardModel?>(null) }
    // Kept with the visit (like the pages), so Back from a collection still shows the group pills.
    val groupsFlow = rememberEntry("collections:groups") { kotlinx.coroutines.flow.MutableStateFlow(emptyList<CollectionGroup>()) }
    val groups by groupsFlow.collectAsState()
    val pager = rememberEntry("collections:$group") {
        LibraryPager<Unit> {
            val serverUrl = runCatching { koin.get<TokenManager>().getServerUrl() }.getOrNull()
            when (val r = repository.listCollections()) {
                is ApiResult.Success -> {
                    groupsFlow.value = r.data.groups.sortedBy { it.sortOrder }
                    ApiResult.Success(Page(orderedCollections(r.data.collections, r.data.groups, group).map { it.toCard(serverUrl) }, hasMore = false, next = null))
                }
                is ApiResult.Error -> r
                is ApiResult.NetworkError -> r
            }
        }
    }
    val state by pager.state.collectAsState()
    LaunchedEffect(pager) { if (pager.state.value.items.isEmpty()) pager.loadMore() }
    val first = rememberInitialFocus(state.items.isNotEmpty())
    Box(Modifier.fillMaxSize()) {
        PageBackdrop(focused)
        key(group) { PosterGrid(
            state,
            onLoadMore = { scope.launch { pager.loadMore() } },
            onRetry = { scope.launch { pager.retry() } },
            onOpen = { onOpenCollection(it.id, it.title) },
            onFocus = { focused = it; lastFocused = it.id },
            emptyText = if (group == null) "No collections yet" else "No collections in this group",
            firstFocus = first,
            restoreId = lastFocused,
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 56.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("Collections", color = Silage.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                        if (groups.isNotEmpty()) ArrowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            item { Pill("All", selected = group == null) { group = null } }
                            groups.forEach { g -> item(key = g.id) { Pill(g.name, selected = group == g.id) { group = g.id } } }
                        }
                    }
                }
            },
        ) }
    }
}
