package dev.silage.app.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.network.ApiResult

sealed interface SearchResults {
    data object Idle : SearchResults
    data object Loading : SearchResults
    data class Done(val cards: List<CardModel>) : SearchResults
    data class Failed(val message: String) : SearchResults
}

/**
 * The library search, ordered the way people expect: the server's search over
 * everything leads with episodes (dozens of shows have one titled "Fallout"), so
 * shows and movies are also asked for on their own, all at once. Shows come first,
 * then movies, then anything else, then episodes; within each, an exact title
 * ("fallout", "The Fallout"), then titles starting with the text, then the
 * server's order. One failed search still shows the others' results.
 */
suspend fun rankedSearch(query: String, search: suspend (query: String, type: String?) -> ApiResult<CatalogResponse>): ApiResult<CatalogResponse> =
    kotlinx.coroutines.coroutineScope {
        val answers = listOf("series", "movie", null).map { type -> async { search(query, type) } }.awaitAll()
        val found = answers.filterIsInstance<ApiResult.Success<CatalogResponse>>()
        if (found.isEmpty()) return@coroutineScope answers.first()
        val seen = HashSet<String>()
        val items = found.flatMap { it.data.items }.filter { seen.add(it.contentId) }
        fun key(text: String) = text.lowercase().removePrefix("the ").filter { it.isLetterOrDigit() }
        val wanted = key(query)
        fun typeRank(type: String?) = when (type) { "series" -> 0; "movie" -> 1; "episode" -> 3; else -> 2 }
        fun titleRank(title: String?) = key(title.orEmpty()).let { if (it == wanted) 0 else if (it.startsWith(wanted)) 1 else 2 }
        ApiResult.Success(CatalogResponse(items = items.sortedWith(compareBy({ typeRank(it.type) }, { titleRank(it.title) }))))
    }

/** Search-as-you-type: waits [debounceMs] after the last keystroke and keeps only the newest query's answer. */
class SearchQuery(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 300,
    private val search: suspend (String) -> ApiResult<CatalogResponse>,
) {
    private val _results = MutableStateFlow<SearchResults>(SearchResults.Idle)
    val results: StateFlow<SearchResults> = _results.asStateFlow()
    private var job: Job? = null

    fun onInput(text: String) {
        job?.cancel()
        val q = text.trim()
        if (q.isEmpty()) {
            _results.value = SearchResults.Idle
            return
        }
        job = scope.launch {
            delay(debounceMs)
            _results.value = SearchResults.Loading
            val r = search(q)
            // Cancelled jobs (a newer query) never get here with a stale answer.
            _results.value = when (r) {
                is ApiResult.Success -> SearchResults.Done(r.data.items.map { it.toCard() })
                is ApiResult.Error -> SearchResults.Failed(r.message)
                is ApiResult.NetworkError -> SearchResults.Failed("Can't reach the server.")
            }
        }
    }
}
