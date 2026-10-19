package dev.silage.app.browse

import dev.silage.app.ui.InterfaceSize
import kotlin.test.Test
import kotlin.test.assertEquals

class CardMenuTest {
    private fun card(kind: String?, watched: Boolean = false) =
        CardModel("id", "T", null, null, null, false, null, watched, null, null, null, null, "", "id", kind = kind)

    @Test fun moviesPlayOpenWatchlistAndWatched() {
        assertEquals(listOf(CardMenuEntry.Play, CardMenuEntry.Open, CardMenuEntry.AddToWatchlist, CardMenuEntry.MarkWatched),
            cardMenuEntries(card("movie"), onWatchlistPage = false))
        assertEquals(listOf(CardMenuEntry.Play, CardMenuEntry.Open, CardMenuEntry.RemoveFromWatchlist, CardMenuEntry.MarkUnwatched),
            cardMenuEntries(card("movie", watched = true), onWatchlistPage = true))
    }

    @Test fun seriesDontPlayFromTheCardAndEpisodesSkipTheWatchlist() {
        assertEquals(listOf(CardMenuEntry.Open, CardMenuEntry.AddToWatchlist, CardMenuEntry.MarkWatched), cardMenuEntries(card("series"), false))
        assertEquals(listOf(CardMenuEntry.Play, CardMenuEntry.Open, CardMenuEntry.MarkWatched), cardMenuEntries(card("episode"), false))
    }

    @Test fun collectionsAndRequestsHaveNoMenu() {
        assertEquals(emptyList(), cardMenuEntries(card(null), false))
        assertEquals(listOf(CardMenuEntry.Open), cardMenuEntries(card("person"), false))
    }

    @Test fun interfaceSizesStepAndFallBack() {
        assertEquals(InterfaceSize.Large, InterfaceSize.Medium.step(1))
        assertEquals(InterfaceSize.Large, InterfaceSize.Large.step(1))
        assertEquals(InterfaceSize.Small, InterfaceSize.Small.step(-1))
        assertEquals(InterfaceSize.Medium, InterfaceSize.of("huge"))
        assertEquals(InterfaceSize.Small, InterfaceSize.of("small"))
    }
}
