package dev.silage.app.requests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestAvailability
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestState
import org.siloserver.silo.model.request.RequestStatus
import org.siloserver.silo.model.request.RequestUserState
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestCardsTest {
    private fun result(
        tmdbId: Int = 603, type: String = RequestMediaType.Movie, availability: String = RequestAvailability.Missing,
        request: RequestState = RequestState(requestable = true), libraryId: String? = null,
    ) = RequestMediaResult(mediaType = type, tmdbId = tmdbId, title = "The Matrix", year = 1999, posterPath = "/p.jpg",
        backdropPath = "/b.jpg", availability = availability, libraryContentId = libraryId, request = request)

    @Test fun aRequestableTitleOpensItsRequestPage() {
        val card = result().toRequestCard()
        assertEquals("request:movie:603", card.id)
        assertEquals("1999 · Movie", card.caption)
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", card.imageUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", card.backdropUrl)
        assertEquals(RequestTarget.Request(RequestMediaType.Movie, 603), requestTargetOf(card))
    }

    @Test fun aRequestedTitleShowsItsStatus() {
        val card = result(type = RequestMediaType.Series, request = RequestState(status = RequestStatus.Pending, state = RequestUserState.Pending)).toRequestCard()
        assertEquals("1999 · Pending", card.caption)
        assertEquals(RequestTarget.Request(RequestMediaType.Series, 603), requestTargetOf(card))
    }

    @Test fun aTitleInTheLibraryOpensTheLibraryItem() {
        val card = result(availability = RequestAvailability.Available, libraryId = "abc").toRequestCard()
        assertEquals(RequestTarget.Library("abc"), requestTargetOf(card))
    }

    @Test fun ordinaryCardsAreNotRequestCards() {
        val card = result().toRequestCard().copy(id = "c1", openTarget = "c1")
        assertNull(requestTargetOf(card))
    }

    @Test fun ownRequestsBecomeCards() {
        val record = MediaRequest(id = "r1", mediaType = RequestMediaType.Movie, tmdbId = 7, title = "Heat", year = 1995,
            status = RequestStatus.Downloading, outcome = RequestOutcome.Active, createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z")
        val card = record.toRequestCard()
        assertEquals("1995 · Downloading", card.caption)
        assertEquals(RequestTarget.Request(RequestMediaType.Movie, 7), requestTargetOf(card))
    }

    @Test fun searchOffersOnlyVideoNotAlreadyInTheLibrary() {
        val kept = requestableResults(listOf(
            result(tmdbId = 1),
            result(tmdbId = 2, availability = RequestAvailability.Available, libraryId = "x"),
            result(tmdbId = 3, type = RequestMediaType.Audiobook),
            result(tmdbId = 4, type = RequestMediaType.Series),
        ))
        assertEquals(listOf(1, 4), kept.map { it.tmdbId })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun searchWaitsForTypingToStopAndRerunsOnUpdates() = runTest {
        val queries = mutableListOf<String>()
        val updates = MutableStateFlow(0)
        val search = RequestSearch(backgroundScope, { q -> queries += q; ApiResult.Success(listOf(result())) }, updates, debounceMs = 300)
        search.onInput("ma")
        advanceTimeBy(100)
        search.onInput("matrix ")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("matrix"), queries)
        assertEquals(1, search.results.value.size)
        updates.value = 1
        runCurrent()
        assertEquals(listOf("matrix", "matrix"), queries)
        search.onInput("")
        assertEquals(emptyList(), search.results.value)
    }
}
