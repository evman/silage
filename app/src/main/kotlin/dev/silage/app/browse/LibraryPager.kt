package dev.silage.app.browse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.network.ApiResult

/** One page of cards and the token for the next ([C] is the server's continuation). */
data class Page<C>(val items: List<CardModel>, val hasMore: Boolean, val next: C?)

data class PagerState(
    val items: List<CardModel> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

/**
 * Pages a grid: appends each page, drops duplicates, stops at the end (or
 * when the server says "more" without a token), and keeps loaded items when
 * a page fails so [retry] can fetch that same page again.
 */
class LibraryPager<C>(private val fetch: suspend (C?) -> ApiResult<Page<C>>) {
    private val _state = MutableStateFlow(PagerState())
    val state: StateFlow<PagerState> = _state.asStateFlow()
    private val lock = Mutex()
    private var next: C? = null
    private var started = false

    suspend fun loadMore() = lock.withLock {
        val s = _state.value
        if (s.endReached || s.error != null) return@withLock
        load()
    }

    suspend fun retry() = lock.withLock {
        if (_state.value.error == null) return@withLock
        _state.update { it.copy(error = null) }
        load()
    }

    private suspend fun load() {
        _state.update { it.copy(loading = true) }
        val token = if (started) next else null
        when (val r = fetch(token)) {
            is ApiResult.Success -> {
                started = true
                next = r.data.next
                _state.update { s ->
                    val seen = s.items.mapTo(HashSet()) { it.id }
                    s.copy(
                        items = s.items + r.data.items.filter { seen.add(it.id) },
                        loading = false,
                        endReached = !r.data.hasMore || r.data.next == null,
                    )
                }
            }
            is ApiResult.Error -> _state.update { it.copy(loading = false, error = r.message) }
            is ApiResult.NetworkError -> _state.update { it.copy(loading = false, error = "Can't reach the server.") }
        }
    }
}
