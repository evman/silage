package dev.silage.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkTest {
    /**
     * Some servers list a poster whose file is gone (HTTP 404) or none at all, yet send its blurred
     * preview: without the title the tile looked like a stuck load. The title shows in both cases.
     */
    @Test fun theTitleShowsWhenThePosterIsMissingOrFailsToLoad() {
        assertEquals(true, artworkShowsTitle(url = null, failed = false))
        assertEquals(true, artworkShowsTitle(url = "https://s/p.webp", failed = true))
        assertEquals(false, artworkShowsTitle(url = "https://s/p.webp", failed = false))
    }

    @Test fun prefersTheImageThenTheBlurThenATitleTile() {
        assertEquals(ArtworkFallback.Image("http://s/p.jpg"), artworkFallback("http://s/p.jpg", "abc", "Dune"))
        assertEquals(ArtworkFallback.Blur("abc"), artworkFallback(null, "abc", "Dune"))
        assertEquals(ArtworkFallback.TitleTile("Dune"), artworkFallback("  ", null, "Dune"))
    }
}
