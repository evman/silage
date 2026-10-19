package dev.silage.app.browse

import dev.silage.app.ui.InterfaceSize
import kotlin.test.Test
import kotlin.test.assertEquals

class CardMenuTest {
    private fun card(kind: String?, watched: Boolean = false, progress: Float? = null, row: String? = null, anchor: String? = null, series: String? = null) =
        CardModel("id", "T", null, null, null, false, progress, watched, null, null, null, null, "", "id", kind = kind,
            homeRow = row, progressUpdatedAt = anchor, seriesId = series)

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

    @Test fun startedTitlesResumeRestartOrReset() {
        val started = card("movie", progress = 0.4f, row = "continue_watching", anchor = "2026-10-19T10:00:00Z")
        assertEquals(listOf(CardMenuEntry.Play, CardMenuEntry.PlayFromStart, CardMenuEntry.Open, CardMenuEntry.AddToWatchlist,
            CardMenuEntry.MarkWatched, CardMenuEntry.ResetProgress, CardMenuEntry.RemoveFromRow), cardMenuEntries(started, false, canDismiss = true))
        assertEquals("Resume", CardMenuEntry.Play.label(started))
        assertEquals("Remove from Continue Watching", CardMenuEntry.RemoveFromRow.label(started))
        // Off Home (no dismissal) there's no "Remove from Continue Watching".
        assertEquals(false, CardMenuEntry.RemoveFromRow in cardMenuEntries(started, false, canDismiss = false))
    }

    @Test fun nextUpNeedsItsSeries() {
        val nextUp = card("episode", progress = null, row = "next_up", series = "s1")
        assertEquals(true, CardMenuEntry.RemoveFromRow in cardMenuEntries(nextUp, false, canDismiss = true))
        assertEquals("Remove from Next Up", CardMenuEntry.RemoveFromRow.label(nextUp))
        assertEquals(false, CardMenuEntry.RemoveFromRow in cardMenuEntries(card("episode", row = "next_up"), false, canDismiss = true))
        assertEquals("Play", CardMenuEntry.Play.label(nextUp))
    }
}
