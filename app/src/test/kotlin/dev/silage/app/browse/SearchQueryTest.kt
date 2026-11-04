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

    private fun item(title: String, type: String, id: String = "$type:$title") = BrowseItem(contentId = id, type = type, title = title)

    /**
     * Typing "fallout" must lead with the show, not hundreds of episodes titled
     * "Fallout"; the server's loose matches (a show matched on its description)
     * must not bury a movie with "Fallout" in its title.
     */
    @Test fun titleMatchesLeadShowsBeforeMoviesAndEpisodesLast() = runTest {
        val asked = mutableListOf<String?>()
        val answer = rankedSearch("fallout") { q, type ->
            asked += type
            assertEquals("fallout", q)
            ApiResult.Success(CatalogResponse(items = when (type) {
                "series" -> listOf(item("Thirst Trap: The Fame. The Fantasy. The Fallout.", "series"), item("Happy Endings", "series"), item("Fallout", "series"))
                "movie" -> listOf(item("Mission: Impossible - Fallout", "movie"), item("Fallout: The Ghoul Log", "movie"), item("The Fallout", "movie"))
                else -> listOf(item("Fallout", "episode", "ep1"), item("Fallout", "series"), item("Fallout", "episode", "ep2"), item("Fallout", "collection"))
            }))
        }
        assertEquals(setOf("series", "movie", null), asked.toSet())
        assertEquals(
            listOf("series:Fallout", "movie:The Fallout", "collection:Fallout",
                "movie:Fallout: The Ghoul Log",
                "series:Thirst Trap: The Fame. The Fantasy. The Fallout.", "movie:Mission: Impossible - Fallout",
                "series:Happy Endings", "ep1", "ep2"),
            assertIs<ApiResult.Success<CatalogResponse>>(answer).data.items.map { it.contentId },
        )
    }

    @Test fun oneFailingSearchStillShowsTheOthersButAllFailingIsAnError() = runTest {
        val partial = rankedSearch("x") { _, type -> if (type == "movie") ApiResult.NetworkError(Exception("down")) else hits("a") }
        assertEquals(listOf("a"), assertIs<ApiResult.Success<CatalogResponse>>(partial).data.items.map { it.contentId })
        assertIs<ApiResult.NetworkError>(rankedSearch("x") { _, _ -> ApiResult.NetworkError(Exception("down")) })
    }

    @Test fun failuresAreReported() = runTest {
        val q = SearchQuery(backgroundScope, debounceMs = 300) { ApiResult.NetworkError(Exception("x")) }
        q.onInput("x"); advanceTimeBy(1_000); runCurrent()
        assertIs<SearchResults.Failed>(q.results.value)
    }
}
