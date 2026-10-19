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
import org.siloserver.silo.network.SiloJson
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.resolveArtworkUrl

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
            when (val r = loadCollections(koin)) {
                is ApiResult.Success -> {
                    val (all, allGroups) = r.data
                    groupsFlow.value = allGroups.sortedBy { it.sortOrder }
                    ApiResult.Success(Page(orderedCollections(all, allGroups, group).map { it.toCard(serverUrl) }, hasMore = false, next = null))
                }
                is ApiResult.Error -> r
                is ApiResult.NetworkError -> ApiResult.Error(0, "collections", "Couldn't load collections: ${r.exception.message ?: r.exception::class.simpleName}")
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

/**
 * GET /api/v2/collections read field by field: one collection the shared
 * model can't decode (a newer server's smart-collection rules, say) must not
 * hide all the others, which is what the strict decode did.
 */
internal suspend fun loadCollections(koin: Koin): ApiResult<Pair<List<SiloCollection>, List<CollectionGroup>>> = try {
    val response = koin.get<io.ktor.client.HttpClient>().get("/api/v2/collections")
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) {
        val message = runCatching { SiloJson.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        ApiResult.Error(response.status.value, "collections", message ?: "The server answered ${response.status.value}.")
    } else {
        ApiResult.Success(parseCollections(SiloJson.parseToJsonElement(text)))
    }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    runCatching { dev.silage.app.CrashLog().record("loading collections", e) }
    ApiResult.NetworkError(e)
}

/** Collections and groups from the list response, skipping entries without an id or name. */
internal fun parseCollections(root: JsonElement): Pair<List<SiloCollection>, List<CollectionGroup>> {
    val obj = root as? JsonObject
    val items = (obj?.get("items") ?: obj?.get("collections") ?: root) as? JsonArray ?: JsonArray(emptyList())
    fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
    fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    val collections = items.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        SiloCollection(
            id = id, name = o.str("name") ?: o.str("title") ?: return@mapNotNull null,
            description = o.str("description"), groupId = o.str("group_id"),
            sortOrder = o.int("sort_order") ?: 0, itemCount = o.int("item_count"),
            posterUrl = o.str("poster_url"), posterThumbhash = o.str("poster_thumbhash"),
        )
    }
    val groups = ((obj?.get("groups")) as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        CollectionGroup(id = o.str("id") ?: return@mapNotNull null, name = o.str("name") ?: return@mapNotNull null, sortOrder = o.int("sort_order") ?: 0)
    }
    return collections to groups
}
