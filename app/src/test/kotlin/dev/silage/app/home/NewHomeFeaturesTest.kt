package dev.silage.app.home

import dev.silage.app.calendar.badgeLabel
import dev.silage.app.requests.seeAllCard
import dev.silage.app.requests.seeAllKey
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.AuthScopeSnapshot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewHomeFeaturesTest {
    private val row = ResolvedSection(id = "s1", sectionType = "recently_added", title = "Recently added",
        items = listOf(SectionItem(contentId = "m1", type = "movie", title = "Dune")))
    private fun owner(profile: String?, server: String = "srv") = AuthScopeSnapshot(serverId = server, profileId = profile, serverUrl = "http://x", profileToken = null)

    @Test fun homeIsCachedPerProfileAndReadBack() = runTest {
        val dir = Files.createTempDirectory("silage-home-cache")
        try {
            val cache = FileHomeCache(dir)
            cache.cacheHomeV2(listOf(row), owner("p1"))
            assertEquals(listOf("m1"), cache.getCachedHomeV2(owner("p1"))?.sections?.single()?.items?.map { it.contentId })
            assertNull(cache.getCachedHomeV2(owner("p2")), "another profile's Home is never shown")
            assertNull(cache.getCachedHomeV2(owner("p1", server = "other")))
            // If-absent never replaces what a screen wrote.
            cache.cacheHomeV2IfAbsent(listOf(row.copy(title = "Other")), owner("p1"))
            assertEquals("Recently added", cache.getCachedHomeV2(owner("p1"))?.sections?.single()?.title)
            // No profile, no cache.
            cache.cacheHomeV2(listOf(row), owner(null))
            assertNull(cache.getCachedHomeV2(owner(null)))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun anExploreAllCardEndsARowWithItsTitle() {
        val card = exploreAllCard(row, landscape = true)
        assertEquals("Explore all", card.title)
        assertEquals("Recently added", card.caption)
        assertEquals(true, card.landscape)
    }

    @Test fun aSeeAllCardNamesItsDiscoverRow() {
        val card = seeAllCard(RequestDiscoverySection(key = "trending", title = "Trending"))
        assertEquals("trending", seeAllKey(card))
        assertNull(seeAllKey(card.copy(id = "req:movie:1")))
    }

    @Test fun calendarMoviesShowTheirReleaseKind() {
        assertEquals("DIGITAL", CalendarItem(contentId = "m", type = "movie", title = "Dune", airDate = "2026-11-11", localAirDate = "2026-11-11", badges = listOf("digital")).badgeLabel())
        assertEquals("PHYSICAL", CalendarItem(contentId = "m", type = "movie", title = "Dune", airDate = "2026-11-11", localAirDate = "2026-11-11", badges = listOf("physical")).badgeLabel())
        assertEquals("PREMIERE", CalendarItem(contentId = "e", type = "episode", title = "Show", airDate = "2026-11-11", localAirDate = "2026-11-11", badges = listOf("series_premiere")).badgeLabel())
        assertNull(CalendarItem(contentId = "e", type = "episode", title = "Show", airDate = "2026-11-11", localAirDate = "2026-11-11").badgeLabel())
    }
}
