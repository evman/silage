package dev.silage.app.browse

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardModelTest {
    @Test fun episodeInContinueWatchingIsLandscapeWithProgressAndSeriesCaption() {
        val item = SectionItem(contentId = "e1", type = "episode", title = "Pilot", seriesId = "s1", seriesTitle = "Severance",
            seasonNumber = 1, episodeNumber = 3, positionSeconds = 600.0, durationSeconds = 2400.0,
            posterUrl = "http://s/p.jpg", backdropUrl = "http://s/b.jpg")
        val c = item.toCard(landscape = true)
        assertEquals("Severance", c.title)
        assertEquals("S1 E3 · Pilot", c.caption)
        assertEquals("http://s/b.jpg", c.imageUrl)
        assertEquals(0.25f, c.progress)
        assertEquals("e1", c.openTarget)
        assertTrue(c.landscape)
    }

    @Test fun movieRowCardIsAPosterWithItsYear() {
        val c = SectionItem(contentId = "m1", type = "movie", title = "Dune", year = 2021, posterUrl = "http://s/p.jpg").toCard(landscape = false)
        assertEquals("Dune", c.title)
        assertEquals("2021", c.caption)
        assertEquals("http://s/p.jpg", c.imageUrl)
        assertNull(c.progress)
    }

    @Test fun landscapeFallsBackToThePosterWithoutABackdrop() {
        assertEquals("http://s/p.jpg", SectionItem(contentId = "m", type = "movie", title = "X", posterUrl = "http://s/p.jpg").toCard(true).imageUrl)
    }

    @Test fun progressRowsAreRecognised() {
        assertTrue(isProgressRow("continue_watching"))
        assertTrue(isProgressRow("next_up"))
        assertFalse(isProgressRow("recently_added"))
    }

    @Test fun missingDurationMeansNoProgressBar() {
        assertNull(SectionItem(contentId = "m", type = "movie", title = "X", positionSeconds = 30.0).toCard(true).progress)
    }

    @Test fun marqueeMetaLineJoinsYearRuntimeAndRating() {
        val c = BrowseItem(contentId = "m", type = "movie", title = "Dune", year = 2021, runtime = 155, contentRating = "PG-13",
            overview = "Paul.", backdropUrl = "http://s/b.jpg").toCard()
        assertEquals("2021 · 2h 35m · PG-13", c.metaLine)
        assertEquals("Paul.", c.overview)
        assertEquals("http://s/b.jpg", c.backdropUrl)
        assertFalse(c.landscape)
    }
}
