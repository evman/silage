package dev.silage.app.browse

import dev.silage.app.settings.ProfileUi
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayId
import org.siloserver.silo.overlays.OverlayPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardBadgesTest {
    private fun prefsWithYear(position: OverlayPosition, enabled: Boolean = true) =
        ProfileUi().badges.let { p ->
            p.copy(items = p.items + (OverlayId.Year to p.items.getValue(OverlayId.Year).copy(enabled = enabled, position = position)))
        }

    @Test fun aBadgeAppearsInItsCornerWhenTheTitleHasTheData() {
        val prefs = prefsWithYear(OverlayPosition.TopLeft)
        assertEquals(listOf("2021"), badgesAt(OverlayPosition.TopLeft, prefs, OverlayData(year = 2021)).map { it.text })
        assertTrue(badgesAt(OverlayPosition.TopRight, prefs, OverlayData(year = 2021)).isEmpty())
    }

    @Test fun noDataMeansNoBadge() {
        val prefs = prefsWithYear(OverlayPosition.TopLeft)
        assertTrue(badgesAt(OverlayPosition.TopLeft, prefs, OverlayData()).none { it.text.isBlank() })
        assertTrue(badgesAt(OverlayPosition.TopLeft, prefs, null).isEmpty())
    }

    @Test fun aBadgeTurnedOffIsNotDrawn() {
        val prefs = prefsWithYear(OverlayPosition.TopLeft, enabled = false)
        assertTrue(badgesAt(OverlayPosition.TopLeft, prefs, OverlayData(year = 2021)).isEmpty())
    }

    @Test fun parsesOnlySixDigitHexColours() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF5C518), parseHex("#f5c518"))
        assertNull(parseHex("gold"))
        assertNull(parseHex("#fff"))
        assertNull(parseHex(null))
    }

    @Test fun anUnwatchedEpisodeHidesItsPictureAndDescriptionOnlyWhenAsked() {
        val ep = BrowseItem(contentId = "e", type = "episode", title = "Pilot", seriesTitle = "Show", posterIsEpisodeStill = true).toCard()
        assertTrue(ep.unwatchedEpisode)
        assertFalse(ep.hidesImage(EpisodeSpoilerPrefs.NONE))
        assertTrue(ep.hidesImage(EpisodeSpoilerPrefs(hideImages = true)))
        assertTrue(ep.hidesOverview(EpisodeSpoilerPrefs(hideOverviews = true)))
        assertFalse(ep.hidesOverview(EpisodeSpoilerPrefs(hideImages = true)))
    }

    @Test fun aWatchedEpisodeAndAMovieAreNeverHidden() {
        val all = EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true)
        val watched = BrowseItem(contentId = "e", type = "episode", title = "Pilot", seriesTitle = "Show", userState = MediaItemUserState(played = true)).toCard()
        assertFalse(watched.hidesImage(all) || watched.hidesOverview(all))
        val movie = BrowseItem(contentId = "m", type = "movie", title = "Dune").toCard()
        assertFalse(movie.hidesImage(all) || movie.hidesOverview(all))
    }

    @Test fun aPictureThatIsNotAnEpisodeStillIsLeftAlone() {
        val ep = BrowseItem(contentId = "e", type = "episode", title = "Pilot", seriesTitle = "Show", posterIsEpisodeStill = false).toCard()
        assertFalse(ep.hidesImage(EpisodeSpoilerPrefs(hideImages = true)))
    }

    @Test fun cardsRememberFavoritesForTheShortcutButton() {
        val c = BrowseItem(contentId = "m", type = "movie", title = "Dune", userState = MediaItemUserState(isFavorite = true)).toCard()
        assertTrue(c.favorite)
    }
}
