package dev.silage.app.update

import java.awt.Desktop
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the small update dialog says: a [message], and a [command] to run when Silage can't install itself. */
data class UpdateNotice(val message: String, val command: String? = null)

/**
 * The update [Updater.state] offers in the sidebar: a new offer replaces the [previous] one and
 * "nothing newer" clears it, but downloading, checking or a failure keep showing the last offer,
 * so a failed attempt to apply it doesn't make the entry disappear until the next check.
 */
fun shownUpdate(previous: UpdateState?, state: UpdateState): UpdateState? = when (state) {
    is UpdateState.Ready, is UpdateState.RestartToFinish, is UpdateState.Available -> state
    UpdateState.Idle -> null
    is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Failed -> previous
}

/**
 * The sidebar's update entry: what it [shown]s and what selecting it does. Everything runs on
 * [scope] (give it an IO scope): applying blocks while the package manager runs.
 */
class UpdateOffer(
    private val updater: Updater,
    private val applier: UpdateApplier,
    private val browse: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _shown = MutableStateFlow<UpdateState?>(null)
    val shown: StateFlow<UpdateState?> = _shown.asStateFlow()

    private val _notice = MutableStateFlow<UpdateNotice?>(null)
    val notice: StateFlow<UpdateNotice?> = _notice.asStateFlow()

    private val busy = AtomicBoolean(false)

    init {
        scope.launch { updater.state.collect { state -> _shown.update { shownUpdate(it, state) } } }
    }

    /** Applies the shown update, restarts into it or opens its page; ignored while one is under way. */
    fun select() {
        val offer = _shown.value ?: return
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                _notice.value = apply(offer)
            } finally {
                busy.set(false)
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** For quitting: a ready update installs silently on the way out (Windows). */
    fun onExit() = applier.onExit(updater.state.value)

    private suspend fun apply(offer: UpdateState): UpdateNotice? = try {
        when (offer) {
            is UpdateState.Available -> {
                try {
                    browse(offer.pageUrl)
                    null
                } catch (_: Exception) {
                    UpdateNotice("Couldn't open your browser. Silage ${offer.version} is at ${offer.pageUrl}")
                }
            }
            is UpdateState.RestartToFinish -> applier.restartOnly().notice()
            // Fetched again even when downloaded: fetchPending verifies the file once more.
            is UpdateState.Ready -> {
                val file = updater.fetchPending()
                if (file == null) UpdateNotice("Couldn't download Silage ${offer.version}. Try again later.") else applier.apply(file).notice()
            }
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UpdateNotice("Couldn't update Silage: ${e.message}")
    }

    private fun ApplyResult.notice(): UpdateNotice? = when (this) {
        ApplyResult.Restarting, ApplyResult.Cancelled -> null
        is ApplyResult.NeedsCommand -> UpdateNotice("Silage can't ask for your password here. Run this in a terminal to update:", command)
        is ApplyResult.Failed -> UpdateNotice(message)
    }
}

/** Opens [url] in the default browser (xdg-open where Java can't); throws when neither works. */
fun openInBrowser(url: String) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
        desktop.browse(URI(url))
        return
    }
    ProcessBuilder("xdg-open", url)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
