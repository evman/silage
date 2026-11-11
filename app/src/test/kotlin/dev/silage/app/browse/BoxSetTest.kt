package dev.silage.app.browse

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.catalog.toSearchCard
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BoxSetTest {
    private val potter = CatalogBoxSet(id = "1241", libraryId = "3", title = "Harry Potter Collection", movieCount = 8, firstYear = 2001, lastYear = 2011)

    @Test fun aBoxSetTargetRoundTripsAndOtherIdsAreNotOne() {
        assertEquals("box_set:3:1241", boxSetTarget(potter))
        assertEquals("3" to "1241", parseBoxSetTarget(boxSetTarget(potter)))
        assertNull(parseBoxSetTarget("movie-123"))
        assertNull(parseBoxSetTarget("box_set:3"))
        assertNull(parseBoxSetTarget("box_set::1241"))
    }

    @Test fun cardsShowYearsUntilStartedThenWatchedProgress() {
        val fresh = potter.toCard()
        assertEquals("2001 – 2011", fresh.caption)
        assertEquals("Box set · 8 films · 2001 – 2011", fresh.metaLine)
        assertEquals("box_set:3:1241", fresh.openTarget)
        assertEquals(null, fresh.kind)
        assertEquals("3 / 8 watched", potter.copy(playedCount = 3).toCard().caption)
        assertEquals(true, potter.copy(playedCount = 8).toCard().watched)
    }

    @Test fun aSearchCardForABoxSetBecomesItsBoxSetCard() {
        val card = potter.toSearchCard().toCard()
        assertEquals("box_set:3:1241", card.openTarget)
        assertEquals("Harry Potter Collection", card.title)
    }

    @Test fun matchingBoxSetsLeadTheSearchResults() = runTest {
        val answer = rankedSearch("harry potter", boxSets = { listOf(potter) }) { _, type ->
            ApiResult.Success(CatalogResponse(items = if (type == "movie") listOf(BrowseItem(contentId = "m1", type = "movie", title = "Harry Potter")) else emptyList()))
        }
        val ids = assertIs<ApiResult.Success<CatalogResponse>>(answer).data.items.map { it.contentId }
        assertEquals(listOf("box_set:3:1241", "m1"), ids)
        // A failed box set lookup leaves the rest of the search alone.
        val without = rankedSearch("x", boxSets = { error("down") }) { _, _ -> ApiResult.Success(CatalogResponse(items = listOf(BrowseItem(contentId = "a", type = "movie", title = "x")))) }
        assertEquals(listOf("a"), assertIs<ApiResult.Success<CatalogResponse>>(without).data.items.map { it.contentId })
    }
}
