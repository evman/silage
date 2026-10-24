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

    @Test fun ageGuidanceJoinsTheContentRatingOnlyWhenAsked() {
        val d = ItemDetail(contentId = "m", type = "movie", title = "X", year = 2020, contentRating = "PG-13", advisoryAge = 13)
        assertEquals("2020 · PG-13", d.toDetailModel().metaLine)
        assertEquals("2020 · PG-13 · 13+", d.toDetailModel(showAdvisoryAge = true).metaLine)
        assertEquals("2020", ItemDetail(contentId = "m", type = "movie", title = "X", year = 2020).toDetailModel(showAdvisoryAge = true).metaLine)
    }

    @Test fun spoilerSettingsHideAnUnwatchedEpisodesStillAndDescription() {
        val e = EpisodeListItem(contentId = "e", seasonNumber = 1, episodeNumber = 2, overview = "He dies.", stillIsEpisodeStill = true)
        val hide = org.siloserver.silo.model.settings.EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true)
        assertEquals("He dies.", e.toRow().overview)
        assertEquals(false, e.toRow().blurStill)
        assertNull(e.toRow(hide).overview)
        assertEquals(true, e.toRow(hide).blurStill)
    }

    @Test fun aWatchedOrStartedEpisodeIsShownInFull() {
        val hide = org.siloserver.silo.model.settings.EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true)
        val watched = EpisodeListItem(contentId = "e", seasonNumber = 1, episodeNumber = 2, overview = "Text", userData = LeafItemUserData(played = true)).toRow(hide)
        assertEquals("Text", watched.overview)
        assertEquals(false, watched.blurStill)
        val started = EpisodeListItem(contentId = "e", seasonNumber = 1, episodeNumber = 2, overview = "Text",
            userData = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 60.0)).toRow(hide)
        assertEquals("Text", started.overview)
    }

    @Test fun anEpisodePageHidesItsOwnDescriptionWhileUnwatched() {
        val hide = org.siloserver.silo.model.settings.EpisodeSpoilerPrefs(hideOverviews = true)
        val d = ItemDetail(contentId = "e", type = "episode", title = "Pilot", overview = "Spoiler")
        assertNull(d.toDetailModel(hide).overview)
        assertEquals("Spoiler", ItemDetail(contentId = "m", type = "movie", title = "Pilot", overview = "Spoiler").toDetailModel(hide).overview)
    }
}
