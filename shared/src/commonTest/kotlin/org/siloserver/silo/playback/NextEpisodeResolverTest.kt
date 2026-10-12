package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.EpisodeListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextEpisodeResolverTest {
    private fun ep(season: Int, episode: Int) =
        EpisodeListItem(contentId = "s${season}e$episode", seasonNumber = season, episodeNumber = episode)

    private val s1 = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
    private val s1s2 = s1 + listOf(ep(2, 1), ep(2, 2))

    @Test fun nextWithinSeason() {
        assertEquals("s1e3", nextEpisodeAfter(s1, currentSeasonNumber = 1, currentEpisodeNumber = 2)?.contentId)
    }

    @Test fun rollsOverToNextSeason() {
        assertEquals("s2e1", nextEpisodeAfter(s1s2, currentSeasonNumber = 1, currentEpisodeNumber = 3)?.contentId)
    }

    @Test fun endOfAvailableRangeReturnsNull() {
        assertNull(nextEpisodeAfter(s1s2, currentSeasonNumber = 2, currentEpisodeNumber = 2))
    }

    @Test fun unorderedPoolStillFindsImmediateNext() {
        val shuffled = listOf(ep(2, 2), ep(1, 1), ep(2, 1), ep(1, 3), ep(1, 2))
        assertEquals("s2e1", nextEpisodeAfter(shuffled, currentSeasonNumber = 1, currentEpisodeNumber = 3)?.contentId)
    }

    @Test fun regularEpisodeNeverAdvancesIntoSpecials() {
        // Season 0 (specials) sorts before season 1, so from S1E3 the next is
        // S2E1, never a season-0 special.
        val withSpecials = listOf(ep(0, 1), ep(0, 2)) + s1s2
        assertEquals("s2e1", nextEpisodeAfter(withSpecials, currentSeasonNumber = 1, currentEpisodeNumber = 3)?.contentId)
    }

    @Test fun fromSpecialAdvancesWithinSpecialsThenIntoRegular() {
        val withSpecials = listOf(ep(0, 1), ep(0, 2)) + s1
        assertEquals("s0e2", nextEpisodeAfter(withSpecials, currentSeasonNumber = 0, currentEpisodeNumber = 1)?.contentId)
        assertEquals("s1e1", nextEpisodeAfter(withSpecials, currentSeasonNumber = 0, currentEpisodeNumber = 2)?.contentId)
    }

    @Test fun emptyPoolReturnsNull() {
        assertNull(nextEpisodeAfter(emptyList(), currentSeasonNumber = 1, currentEpisodeNumber = 1))
    }

    @Test fun sameSeasonGapReturnsNextAvailable() {
        // Episode 2 is missing; from E1 the next available is E3.
        val gapped = listOf(ep(1, 1), ep(1, 3), ep(1, 4))
        assertEquals("s1e3", nextEpisodeAfter(gapped, currentSeasonNumber = 1, currentEpisodeNumber = 1)?.contentId)
    }

    @Test fun currentNotInPoolStillFindsNextGreater() {
        // Current S1E2 isn't in the pool; still resolves to S1E3.
        val poolWithoutCurrent = listOf(ep(1, 1), ep(1, 3))
        assertEquals("s1e3", nextEpisodeAfter(poolWithoutCurrent, currentSeasonNumber = 1, currentEpisodeNumber = 2)?.contentId)
    }

    @Test fun regularAtEndWithOnlyEarlierSpecialsReturnsNull() {
        val onlySpecialsBelow = listOf(ep(0, 1), ep(0, 2), ep(1, 1))
        assertNull(nextEpisodeAfter(onlySpecialsBelow, currentSeasonNumber = 1, currentEpisodeNumber = 1))
    }
}
