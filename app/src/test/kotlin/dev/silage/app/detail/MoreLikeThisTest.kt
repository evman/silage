package dev.silage.app.detail

import org.siloserver.silo.model.catalog.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals

class MoreLikeThisTest {
    @Test fun moviesAndShowsGetTheRowButEpisodesDont() {
        assertEquals(listOf(true, true, true, false, false), listOf("movie", "series", "Show", "episode", null).map(::showsMoreLikeThis))
    }

    @Test fun theTitleItselfAndRepeatsAreLeftOut() {
        val items = listOf("a", "self", "b", "a").map { BrowseItem(contentId = it, type = "movie", title = it.uppercase()) }
        val cards = moreLikeThis(items, "self")
        assertEquals(listOf("a", "b"), cards.map { it.id })
        assertEquals("a", cards.first().openTarget)
        assertEquals(false, cards.first().landscape)
    }
}
