package dev.silage.app.playback

import dev.silage.player.EndReason
import dev.silage.player.MpvPlayer
import dev.silage.player.PlayerEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A player the session can open and must close. */
interface OpenedPlayer : PlayerPort, AutoCloseable

fun MpvPlayer.asOpened(): OpenedPlayer {
    val port = asPort()
    val mpv = this
    return object : OpenedPlayer, PlayerPort by port {
        override fun close() = mpv.close()
    }
}

sealed interface PlayOutcome {
    /** Stopped before the end (Back, or a source/stream switch). */
    data object Finished : PlayOutcome
    /** Played to the end of the file: time for "Up next". */
    data object Completed : PlayOutcome
    data class Failed(val message: String) : PlayOutcome
}

/**
 * One play request end to end: start the server session, open the player,
 * run it, and report why it ended. The player window closes before the
 * server stop call, so a slow or offline server never leaves a frozen
 * fullscreen window behind.
 */
class PlaybackSession(
    private val launch: suspend (contentId: String) -> LaunchResult,
    private val openPlayer: (LaunchResult.Ready) -> OpenedPlayer,
    private val progress: suspend (sessionId: String, position: Double, paused: Boolean) -> Unit,
    private val stopSession: suspend (sessionId: String) -> Unit,
) {
    suspend fun play(contentId: String): PlayOutcome {
        val ready = when (val r = launch(contentId)) {
            is LaunchResult.Failed -> return PlayOutcome.Failed(r.message)
            is LaunchResult.Ready -> r
        }
        val player = try {
            openPlayer(ready)
        } catch (t: Throwable) {
            withContext(NonCancellable) { runCatching { stopSession(ready.sessionId) } }
            return PlayOutcome.Failed("Couldn't start the video player: ${t.message}")
        }
        try {
            val end = PlaybackController(
                player,
                progress = progress,
                stop = { sessionId -> player.close(); stopSession(sessionId) },
            ).run(ready)
            return when {
                end is PlayerEvent.Ended && end.reason == EndReason.ERROR -> PlayOutcome.Failed("Playback failed: ${end.error ?: "unknown error"}")
                end is PlayerEvent.Ended && end.reason == EndReason.EOF -> PlayOutcome.Completed
                else -> PlayOutcome.Finished
            }
        } finally {
            player.close()
        }
    }
}
