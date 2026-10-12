package dev.silage.app.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
