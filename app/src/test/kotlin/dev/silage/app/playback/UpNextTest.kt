package dev.silage.app.playback

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.EpisodeListItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpNextTest {
    private fun ep(season: Int, episode: Int) = EpisodeListItem(contentId = "s${season}e$episode", seasonNumber = season, episodeNumber = episode)
    private val seasons = mapOf(1 to listOf(ep(1, 1), ep(1, 2), ep(1, 3)), 2 to listOf(ep(2, 1), ep(2, 2)), 0 to listOf(ep(0, 1), ep(0, 2)))
    private val loads = mutableListOf<Int>()
    private suspend fun next(season: Int?, episode: Int?, series: String? = "show") =
        findNextEpisode(PlaybackMeta(seriesId = series, seasonNumber = season, episodeNumber = episode)) { _, s -> loads += s; seasons[s] }

    @Test fun nextIsTheFollowingEpisodeInTheSeason() = runTest {
        assertEquals("s1e2", next(1, 1)?.contentId)
        assertEquals(listOf(1), loads, "the next season isn't fetched when not needed")
    }

    @Test fun theLastEpisodeOfASeasonRollsIntoTheNextSeason() = runTest {
        assertEquals("s2e1", next(1, 3)?.contentId)
    }

    @Test fun theFinaleHasNoNextEpisode() = runTest {
        assertNull(next(2, 2))
    }

    @Test fun specialsStayWithinSpecials() = runTest {
        assertEquals("s0e2", next(0, 1)?.contentId)
        assertNull(next(0, 2), "a special never rolls into season 1")
    }

    @Test fun moviesAndUnknownEpisodesHaveNoNext() = runTest {
        assertNull(next(1, 1, series = null))
        assertNull(next(null, 1))
    }
}
