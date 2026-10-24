package dev.silage.app.browse

import dev.silage.app.ui.InterfaceSize
import kotlin.test.Test
import kotlin.test.assertEquals

class CardMenuTest {
    private fun card(kind: String?, watched: Boolean = false, progress: Float? = null, row: String? = null, anchor: String? = null, series: String? = null,
        inWatchlist: Boolean = false, id: String = "id") =
        CardModel(id, "T", null, null, null, false, progress, watched, null, null, null, null, "", id, kind = kind,
            homeRow = row, progressUpdatedAt = anchor, seriesId = series, inWatchlist = inWatchlist)

    @kotlin.test.BeforeTest fun forgetMenuChanges() = WatchlistMemory.clear()

    @Test fun titlesAlreadyOnTheWatchlistOfferToRemoveThemAnywhere() {
        assertEquals(true, CardMenuEntry.RemoveFromWatchlist in cardMenuEntries(card("movie", inWatchlist = true), onWatchlistPage = false))
        assertEquals(true, CardMenuEntry.RemoveFromWatchlist in cardMenuEntries(card("series", row = "watchlist"), onWatchlistPage = false))
        // A change made from a menu shows in the next menu before the page reloads.
        WatchlistMemory.set("m2", true)
        assertEquals(true, CardMenuEntry.RemoveFromWatchlist in cardMenuEntries(card("movie", id = "m2"), onWatchlistPage = false))
        WatchlistMemory.set("m2", false)
        assertEquals(true, CardMenuEntry.AddToWatchlist in cardMenuEntries(card("movie", id = "m2", inWatchlist = true), onWatchlistPage = false))
    }

    @Test fun continueWatchingCanBeRemovedWithoutTheServersAnchor() {
        val started = card("episode", progress = 0.3f, row = "continue_watching", anchor = null)
        assertEquals(true, CardMenuEntry.RemoveFromRow in cardMenuEntries(started, false, canDismiss = true))
    }

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

    @Test fun aNextEpisodeIsDismissedByItsSeriesEvenInContinueWatching() {
        // Silo puts the next episode of a show in Continue Watching (item_source next_up); it has no saved progress.
        val merged = card("episode", row = "continue_watching", series = "series:a").copy(itemSource = "next_up")
        assertEquals(HomeDismissal("next_up", "series:a"), homeDismissal(merged))
        assertEquals(HomeDismissal("next_up", "series:a"), homeDismissal(card("episode", row = "next_up", series = "series:a", anchor = "t")))
        assertEquals(HomeDismissal("continue_watching", "t"), homeDismissal(card("episode", row = "continue_watching", series = "series:a", anchor = "t")))
        // An unstarted episode without the source still has its series to go by.
        assertEquals(HomeDismissal("next_up", "series:a"), homeDismissal(card("episode", row = "continue_watching", series = "series:a")))
        assertEquals(null, homeDismissal(card("movie", row = "continue_watching")))
    }

    @Test fun anAcceptedRemovalSaysSoAndAFailedOneSaysWhy() = kotlinx.coroutines.runBlocking {
        val c = card("episode", row = "continue_watching", series = "series:a")
        assertEquals("Removed from Continue Watching" to true, runRemoval({ null }, c, "Removed from Continue Watching"))
        assertEquals("Couldn't remove it: nope" to false, runRemoval({ "Couldn't remove it: nope" }, c, "Removed"))
        assertEquals(false, runRemoval({ error("boom") }, c, "Removed").second)
        assertEquals("Couldn't remove it from here." to false, runRemoval(null, c, "Removed"))
    }

    @Test fun anEpisodeWithItsShowOffersGoToShowAfterOpen() {
        val episode = card("episode", series = "series:a").copy(seasonNumber = 3)
        val entries = cardMenuEntries(episode, false)
        assertEquals(entries.indexOf(CardMenuEntry.Open) + 1, entries.indexOf(CardMenuEntry.GoToShow))
        assertEquals("Go to show", CardMenuEntry.GoToShow.label(episode))
        assertEquals(false, CardMenuEntry.GoToShow in cardMenuEntries(card("episode"), false))
        assertEquals(false, CardMenuEntry.GoToShow in cardMenuEntries(card("movie", series = "x"), false))
    }

    @Test fun cardsKeepTheEpisodesShowAndSeason() {
        val fromSearch = org.siloserver.silo.model.catalog.BrowseItem(contentId = "e", type = "episode", title = "Pilot",
            seriesId = "series:a", seriesTitle = "A", seasonNumber = 2).toCard()
        assertEquals("series:a", fromSearch.seriesId)
        assertEquals(2, fromSearch.seasonNumber)
        val fromHome = org.siloserver.silo.model.section.SectionItem(contentId = "e", type = "episode", title = "Pilot",
            seriesId = "series:a", seriesTitle = "A", seasonNumber = 4).toCard(landscape = true)
        assertEquals(4, fromHome.seasonNumber)
    }
}
