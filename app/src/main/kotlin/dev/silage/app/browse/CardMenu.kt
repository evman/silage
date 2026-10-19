package dev.silage.app.browse

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.staticCompositionLocalOf
import dev.silage.app.detail.ItemActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a card's right-click menu can do, provided by the page around the cards. */
class CardMenu(
    val play: (contentId: String) -> Unit,
    val playFromStart: (contentId: String) -> Unit,
    val open: (contentId: String) -> Unit,
    private val actions: ItemActions,
    private val scope: CoroutineScope,
    /** A short confirmation ("Added to your watchlist"). */
    private val notify: (String) -> Unit,
    /** The cards are the watchlist itself, so the menu offers to remove. */
    val onWatchlistPage: Boolean = false,
    /** Removes a card from its Home row (Continue Watching, Next Up); only Home offers it. */
    private val dismiss: ((CardModel) -> Unit)? = null,
    /** Called after a change the page shows (progress reset), so it can reload. */
    private val changed: () -> Unit = {},
) {
    /** This menu on Home: removing from a row, and reloading Home after a change. */
    fun forHome(dismiss: (CardModel) -> Unit, changed: () -> Unit) =
        CardMenu(play, playFromStart, open, actions, scope, notify, onWatchlistPage, dismiss, changed)

    fun items(card: CardModel): List<ContextMenuItem> = cardMenuEntries(card, onWatchlistPage, canDismiss = dismiss != null).map { entry ->
        ContextMenuItem(entry.label(card)) {
            when (entry) {
                CardMenuEntry.Play -> play(card.openTarget)
                CardMenuEntry.PlayFromStart -> playFromStart(card.openTarget)
                CardMenuEntry.Open -> open(card.openTarget)
                CardMenuEntry.ResetProgress -> act("Progress reset") {
                    // Silo keeps no separate "clear progress": marking watched ends the
                    // saved position, and unmarking leaves the title unwatched from the start.
                    actions.setWatched(card.id, true) && actions.setWatched(card.id, false)
                }
                CardMenuEntry.RemoveFromRow -> { dismiss?.invoke(card); notify(if (isNextUp(card)) "Removed from Next Up" else "Removed from Continue Watching") }
                CardMenuEntry.AddToWatchlist -> act("Added to your watchlist") { actions.setWatchlist(card.id, true) }
                CardMenuEntry.RemoveFromWatchlist -> act("Removed from your watchlist") { actions.setWatchlist(card.id, false) }
                CardMenuEntry.MarkWatched -> act("Marked as watched") { actions.setWatched(card.id, true) }
                CardMenuEntry.MarkUnwatched -> act("Marked as unwatched") { actions.setWatched(card.id, false) }
            }
        }
    }

    private fun act(done: String, call: suspend () -> Boolean) {
        scope.launch {
            val ok = runCatching { call() }.getOrDefault(false)
            notify(if (ok) done else "Couldn't save that change. Try again.")
            if (ok) changed()
        }
    }
}

private val progressRows = setOf("continue_watching", "in_progress", "next_up", "up_next")
private fun isNextUp(card: CardModel) = card.homeRow == "next_up" || card.homeRow == "up_next"

enum class CardMenuEntry(private val text: String) {
    Play("Play"), PlayFromStart("Play from beginning"), Open("Open"), AddToWatchlist("Add to watchlist"),
    RemoveFromWatchlist("Remove from watchlist"), MarkWatched("Mark as watched"), MarkUnwatched("Mark as unwatched"),
    ResetProgress("Reset progress"), RemoveFromRow("Remove from Continue Watching"),
    ;

    fun label(card: CardModel): String = when {
        this == Play && card.progress != null -> "Resume"
        this == RemoveFromRow && isNextUp(card) -> "Remove from Next Up"
        else -> text
    }
}

private val playable = setOf("movie", "episode", "video")
private val watchable = setOf("movie", "episode", "series", "show", "video")

/** The menu for [card]: none for collections and requests (no [CardModel.kind]). */
fun cardMenuEntries(card: CardModel, onWatchlistPage: Boolean, canDismiss: Boolean = false): List<CardMenuEntry> {
    val kind = card.kind?.lowercase() ?: return emptyList()
    val started = card.progress != null
    return buildList {
        if (kind in playable) {
            add(CardMenuEntry.Play)
            if (started) add(CardMenuEntry.PlayFromStart)
        }
        add(CardMenuEntry.Open)
        if (kind != "episode" && kind in watchable) add(if (onWatchlistPage) CardMenuEntry.RemoveFromWatchlist else CardMenuEntry.AddToWatchlist)
        if (kind in watchable) add(if (card.watched) CardMenuEntry.MarkUnwatched else CardMenuEntry.MarkWatched)
        if (started && kind in playable) add(CardMenuEntry.ResetProgress)
        val anchored = if (isNextUp(card)) card.seriesId != null else card.progressUpdatedAt != null
        if (canDismiss && card.homeRow in progressRows && anchored) add(CardMenuEntry.RemoveFromRow)
    }
}

val LocalCardMenu = staticCompositionLocalOf<CardMenu?> { null }
