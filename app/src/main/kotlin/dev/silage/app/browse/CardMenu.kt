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
    /**
     * Removes a card from its Home row (Continue Watching, Next Up); only Home offers it.
     * Returns null when the server accepted it, else what went wrong.
     */
    private val dismiss: (suspend (CardModel) -> String?)? = null,
    /** Called after a change the page shows (progress reset), so it can reload. */
    private val changed: () -> Unit = {},
) {
    /** This menu on Home: removing from a row, and reloading Home after a change. */
    fun forHome(dismiss: suspend (CardModel) -> String?, changed: () -> Unit) =
        CardMenu(play, playFromStart, open, actions, scope, notify, onWatchlistPage, dismiss, changed)

    /** The poster's shortcut buttons: save the change, and say so when it didn't take. */
    suspend fun quickFavorite(id: String, on: Boolean): Boolean =
        runCatching { actions.setFavorite(id, on) }.getOrDefault(false).also { if (!it) notify("Couldn't save that change. Try again.") }

    suspend fun quickWatched(id: String, on: Boolean): Boolean =
        runCatching { actions.setWatched(id, on) }.getOrDefault(false).also { if (!it) notify("Couldn't save that change. Try again.") }

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
                CardMenuEntry.RemoveFromRow -> when {
                    homeDismissal(card) != null -> removeFromRow(card, "Removed from ${entry.label(card).removePrefix("Remove from ")}")
                    // No dismissal anchor from this server: clearing the saved position takes
                    // the title off Continue Watching just the same.
                    else -> act("Removed from Continue Watching") {
                        actions.setWatched(card.id, true) && actions.setWatched(card.id, false)
                    }
                }
                CardMenuEntry.AddToWatchlist -> act("Added to your watchlist") {
                    actions.setWatchlist(card.id, true).also { if (it) WatchlistMemory.set(card.id, true) }
                }
                CardMenuEntry.RemoveFromWatchlist -> act("Removed from your watchlist") {
                    actions.setWatchlist(card.id, false).also { if (it) WatchlistMemory.set(card.id, false) }
                }
                CardMenuEntry.MarkWatched -> act("Marked as watched") { actions.setWatched(card.id, true) }
                CardMenuEntry.MarkUnwatched -> act("Marked as unwatched") { actions.setWatched(card.id, false) }
            }
        }
    }

    /** Asks the server, and says what it answered: the row reloads only once it accepted (it drops the whole show). */
    private fun removeFromRow(card: CardModel, done: String) {
        scope.launch {
            val (message, accepted) = runRemoval(dismiss, card, done)
            notify(message)
            if (accepted) changed()
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

/**
 * Runs [dismiss] for [card]: [done] and true when the server accepted it, else what went wrong and false.
 * [dismiss] answers null for success, so a missing [dismiss] is checked first rather than with `?:` on
 * its answer (which once turned every success into "Couldn't remove it from here.").
 */
internal suspend fun runRemoval(dismiss: (suspend (CardModel) -> String?)?, card: CardModel, done: String): Pair<String, Boolean> {
    if (dismiss == null) return "Couldn't remove it from here." to false
    val problem = runCatching { dismiss(card) }.getOrElse { "Couldn't remove it: ${it.message}" }
    return if (problem == null) done to true else problem to false
}

/**
 * Watchlist changes made from right-click menus this session, so the next menu on the
 * same title offers the opposite action before its page reloads.
 */
object WatchlistMemory {
    private val changed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun set(id: String, on: Boolean) { changed[id] = on }
    fun isOn(card: CardModel, onWatchlistPage: Boolean): Boolean =
        changed[card.id] ?: (onWatchlistPage || card.inWatchlist || card.homeRow?.contains("watchlist") == true)
    internal fun clear() = changed.clear()
}

private val progressRows = setOf("continue_watching", "in_progress", "next_up", "up_next")
private fun isNextUp(card: CardModel) = card.homeRow == "next_up" || card.homeRow == "up_next"

/** Which Home surface to dismiss a card from, and the anchor the server needs for it. */
data class HomeDismissal(val surface: String, val anchor: String)

/**
 * How to take [card] off Home. A next episode (in Next Up, or one the server put in Continue
 * Watching as `item_source: next_up`) has no saved progress, so it is dismissed by its series;
 * a started title by when its progress was saved. Silo then drops the whole show from both rows.
 * Null when the server sent neither anchor.
 */
fun homeDismissal(card: CardModel): HomeDismissal? {
    val series = card.seriesId?.takeIf { it.isNotBlank() }
    val saved = card.progressUpdatedAt?.takeIf { it.isNotBlank() }
    val nextEpisode = isNextUp(card) || card.itemSource == "next_up"
    return when {
        nextEpisode && series != null -> HomeDismissal("next_up", series)
        saved != null -> HomeDismissal("continue_watching", saved)
        series != null -> HomeDismissal("next_up", series)
        else -> null
    }
}

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
        if (kind != "episode" && kind in watchable) add(if (WatchlistMemory.isOn(card, onWatchlistPage)) CardMenuEntry.RemoveFromWatchlist else CardMenuEntry.AddToWatchlist)
        if (kind in watchable) add(if (card.watched) CardMenuEntry.MarkUnwatched else CardMenuEntry.MarkWatched)
        if (started && kind in playable) add(CardMenuEntry.ResetProgress)
        // Continue Watching works with or without the server's dismissal anchor (see CardMenu.items);
        // Next Up can only be dismissed for its series.
        val removable = if (isNextUp(card)) card.seriesId != null else true
        if (canDismiss && card.homeRow in progressRows && removable) add(CardMenuEntry.RemoveFromRow)
    }
}

val LocalCardMenu = staticCompositionLocalOf<CardMenu?> { null }
