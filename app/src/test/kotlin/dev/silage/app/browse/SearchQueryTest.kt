package dev.silage.app.browse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SearchQueryTest {
    private fun hits(vararg ids: String): ApiResult<CatalogResponse> =
        ApiResult.Success(CatalogResponse(items = ids.map { BrowseItem(contentId = it, type = "movie", title = it) }))

    @Test fun typingQuicklyIssuesOneSearchForTheFinalText() = runTest {
        val asked = mutableListOf<String>()
        val q = SearchQuery(backgroundScope, debounceMs = 300) { asked += it; hits("dune") }
        q.onInput("d"); advanceTimeBy(100)
        q.onInput("du"); advanceTimeBy(100)
        q.onInput("dune"); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("dune"), asked)
        assertEquals(listOf("dune"), (q.results.value as SearchResults.Done).cards.map { it.id })
    }

    @Test fun blankQueryGoesIdleWithoutSearching() = runTest {
        val asked = mutableListOf<String>()
        val q = SearchQuery(backgroundScope, debounceMs = 300) { asked += it; hits() }
        q.onInput("   "); advanceTimeBy(1_000); runCurrent()
        assertEquals(emptyList(), asked)
        assertIs<SearchResults.Idle>(q.results.value)
    }

    @Test fun aSlowEarlierAnswerNeverOverwritesANewerOne() = runTest {
        val slow = CompletableDeferred<ApiResult<CatalogResponse>>()
        val q = SearchQuery(backgroundScope, debounceMs = 300) { if (it == "old") slow.await() else hits("new") }
        q.onInput("old"); advanceTimeBy(400)
        q.onInput("new"); advanceTimeBy(1_000); runCurrent()
        slow.complete(hits("old")); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("new"), (q.results.value as SearchResults.Done).cards.map { it.id })
    }

    @Test fun failuresAreReported() = runTest {
        val q = SearchQuery(backgroundScope, debounceMs = 300) { ApiResult.NetworkError(Exception("x")) }
        q.onInput("x"); advanceTimeBy(1_000); runCurrent()
        assertIs<SearchResults.Failed>(q.results.value)
    }
}
