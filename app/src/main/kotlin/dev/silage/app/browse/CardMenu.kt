package dev.silage.app.browse

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.staticCompositionLocalOf
import dev.silage.app.detail.ItemActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a card's right-click menu can do, provided by the page around the cards. */
class CardMenu(
    val play: (contentId: String) -> Unit,
    val open: (contentId: String) -> Unit,
    private val actions: ItemActions,
    private val scope: CoroutineScope,
    /** A short confirmation ("Added to your watchlist"). */
    private val notify: (String) -> Unit,
    /** The cards are the watchlist itself, so the menu offers to remove. */
    val onWatchlistPage: Boolean = false,
) {
    fun items(card: CardModel): List<ContextMenuItem> = cardMenuEntries(card, onWatchlistPage).map { entry ->
        ContextMenuItem(entry.label) {
            when (entry) {
                CardMenuEntry.Play -> play(card.openTarget)
                CardMenuEntry.Open -> open(card.openTarget)
                CardMenuEntry.AddToWatchlist -> act("Added to your watchlist") { actions.setWatchlist(card.id, true) }
                CardMenuEntry.RemoveFromWatchlist -> act("Removed from your watchlist") { actions.setWatchlist(card.id, false) }
                CardMenuEntry.MarkWatched -> act("Marked as watched") { actions.setWatched(card.id, true) }
                CardMenuEntry.MarkUnwatched -> act("Marked as unwatched") { actions.setWatched(card.id, false) }
            }
        }
    }

    private fun act(done: String, call: suspend () -> Boolean) {
        scope.launch { notify(if (runCatching { call() }.getOrDefault(false)) done else "Couldn't save that change. Try again.") }
    }
}

enum class CardMenuEntry(val label: String) {
    Play("Play"), Open("Open"), AddToWatchlist("Add to watchlist"), RemoveFromWatchlist("Remove from watchlist"),
    MarkWatched("Mark as watched"), MarkUnwatched("Mark as unwatched"),
}

private val playable = setOf("movie", "episode", "video")
private val watchable = setOf("movie", "episode", "series", "show", "video")

/** The menu for [card]: none for collections and requests (no [CardModel.kind]). */
fun cardMenuEntries(card: CardModel, onWatchlistPage: Boolean): List<CardMenuEntry> {
    val kind = card.kind?.lowercase() ?: return emptyList()
    return buildList {
        if (kind in playable) add(CardMenuEntry.Play)
        add(CardMenuEntry.Open)
        if (kind != "episode" && kind in watchable) add(if (onWatchlistPage) CardMenuEntry.RemoveFromWatchlist else CardMenuEntry.AddToWatchlist)
        if (kind in watchable) add(if (card.watched) CardMenuEntry.MarkUnwatched else CardMenuEntry.MarkWatched)
    }
}

val LocalCardMenu = staticCompositionLocalOf<CardMenu?> { null }
