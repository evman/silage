package dev.silage.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkTest {
    @Test fun prefersTheImageThenTheBlurThenATitleTile() {
        assertEquals(ArtworkFallback.Image("http://s/p.jpg"), artworkFallback("http://s/p.jpg", "abc", "Dune"))
        assertEquals(ArtworkFallback.Blur("abc"), artworkFallback(null, "abc", "Dune"))
        assertEquals(ArtworkFallback.TitleTile("Dune"), artworkFallback("  ", null, "Dune"))
    }
}
