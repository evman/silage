package dev.silage.app.playback

import dev.silage.player.MpvPlayer
import dev.silage.player.PlayerEvent
import dev.silage.player.PlayerState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The parts of MpvPlayer the controller needs (fakeable in tests). */
interface PlayerPort {
    val state: StateFlow<PlayerState>
    val events: Flow<PlayerEvent>
    fun load(url: String, headers: Map<String, String>, startSeconds: Double?)
    fun stop()
}

fun MpvPlayer.asPort(): PlayerPort = object : PlayerPort {
    override val state = this@asPort.state
    override val events = this@asPort.events
    override fun load(url: String, headers: Map<String, String>, startSeconds: Double?) = this@asPort.load(url, headers, startSeconds)
    override fun stop() = this@asPort.stop()
}

/**
 * Runs one playback session: loads the stream, reports progress on an
 * interval and on every pause/resume, and always stops the server session
 * when playback ends, mpv shuts down, or the caller cancels.
 */
class PlaybackController(
    private val player: PlayerPort,
    private val progress: suspend (sessionId: String, position: Double, paused: Boolean) -> Unit,
    private val stop: suspend (sessionId: String) -> Unit,
    private val intervalMs: Long = 10_000,
) {
    suspend fun run(ready: LaunchResult.Ready) {
        try {
            coroutineScope {
                // Subscribe before load: a stream that fails instantly emits
                // Ended during load(), and a later subscriber would miss it.
                val finished = async(start = CoroutineStart.UNDISPATCHED) {
                    player.events.first { it is PlayerEvent.Ended || it is PlayerEvent.Shutdown }
                }
                player.load(ready.url, ready.headers, ready.startSeconds)
                val ticker = launch {
                    while (true) {
                        delay(intervalMs)
                        val s = player.state.value
                        if (s.loaded) progress(ready.sessionId, s.positionSeconds, s.paused)
                    }
                }
                val pauses = launch {
                    player.state.distinctUntilChangedBy { it.paused }.drop(1).collect { s ->
                        progress(ready.sessionId, s.positionSeconds, s.paused)
                    }
                }
                finished.await()
                ticker.cancel(); pauses.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                player.stop()
                runCatching { stop(ready.sessionId) }
            }
        }
    }
}
