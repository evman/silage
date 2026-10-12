package dev.silage.app.detail

import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailModelTest {
    private val resumable = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 2530.0, durationSeconds = 9300.0)

    @Test fun movieMetaLineAndResumeLabel() {
        val d = ItemDetail(contentId = "m", type = "movie", title = "Dune", year = 2021, runtime = 155, contentRating = "PG-13",
            genres = listOf("Sci-Fi", "Adventure"), userData = resumable)
        val m = d.toDetailModel()
        assertEquals("2021 · 2h 35m · PG-13 · Sci-Fi, Adventure", m.metaLine)
        assertEquals("Resume from 42:10", m.playLabel)
        assertTrue(m.canPlayFromStart)
        assertFalse(m.isSeries)
    }

    @Test fun unwatchedMovieJustPlays() {
        val m = ItemDetail(contentId = "m", type = "movie", title = "X").toDetailModel()
        assertEquals("Play", m.playLabel)
        assertFalse(m.canPlayFromStart)
    }

    @Test fun watchedMovieOffersPlayAgain() {
        assertEquals("Play", ItemDetail(contentId = "m", type = "movie", title = "X", userData = resumable.copy(played = true)).toDetailModel().playLabel)
    }

    @Test fun seriesIsRecognisedWithSeasonCountInTheMetaLine() {
        val m = ItemDetail(contentId = "s", type = "series", title = "Severance", year = 2022, seasonCount = 2, contentRating = "TV-MA").toDetailModel()
        assertTrue(m.isSeries)
        assertEquals("2022 · 2 seasons · TV-MA", m.metaLine)
    }

    @Test fun episodeRowShowsNumberRuntimeAndProgress() {
        val r = EpisodeListItem(contentId = "e", seasonNumber = 1, episodeNumber = 4, title = "The You You Are", runtime = 51,
            userData = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 1530.0, durationSeconds = 3060.0)).toRow()
        assertEquals("4", r.number)
        assertEquals("51m", r.runtime)
        assertEquals(0.5f, r.progress)
        assertEquals("The You You Are", r.title)
    }

    @Test fun untitledEpisodeGetsANumberedTitle() {
        assertEquals("Episode 7", EpisodeListItem(contentId = "e", seasonNumber = 1, episodeNumber = 7).toRow().title)
    }

    @Test fun nextEpisodePrefersInProgressThenFirstUnwatched() {
        fun ep(n: Int, played: Boolean = false, progress: Boolean = false) =
            EpisodeListItem(contentId = "e$n", seasonNumber = 1, episodeNumber = n, userData = LeafItemUserData(played = played, isInProgress = progress))
        assertEquals("e3", nextEpisode(listOf(ep(1, played = true), ep(2), ep(3, progress = true)))?.contentId)
        assertEquals("e2", nextEpisode(listOf(ep(1, played = true), ep(2), ep(3)))?.contentId)
        assertEquals("e1", nextEpisode(listOf(ep(1, played = true), ep(2, played = true)))?.contentId)
        assertNull(nextEpisode(emptyList()))
    }
}
