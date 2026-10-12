package dev.silage.app.playback

import dev.silage.player.EndReason
import dev.silage.player.PlayerEvent
import dev.silage.player.PlayerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakePlayer(private val onLoad: FakePlayer.() -> Unit = {}) : PlayerPort {
    override val state = MutableStateFlow(PlayerState())
    override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    val loaded = mutableListOf<String>()
    var stopped = false
    override fun load(url: String, headers: Map<String, String>, startSeconds: Double?) { loaded += url; onLoad() }
    override fun stop() { stopped = true }
}

class PlaybackControllerTest {
    private val ready = LaunchResult.Ready("sess-1", "http://nas/x", emptyMap(), null)

    @Test fun reportsProgressEveryIntervalAndStopsAtEof() = runTest {
        val player = FakePlayer()
        val progress = mutableListOf<Double>()
        val stops = mutableListOf<String>()
        val job = launch {
            PlaybackController(player, { _, pos, _ -> progress += pos }, { stops += it }, intervalMs = 10_000).run(ready)
        }
        runCurrent()
        assertEquals(listOf("http://nas/x"), player.loaded)
        player.state.value = PlayerState(positionSeconds = 12.0, loaded = true)
        advanceTimeBy(10_001)
        player.state.value = PlayerState(positionSeconds = 22.0, loaded = true)
        advanceTimeBy(10_000)
        player.events.emit(PlayerEvent.Ended(EndReason.EOF, null))
        job.join()
        assertTrue(progress.containsAll(listOf(12.0, 22.0)), "progress=$progress")
        assertEquals(listOf("sess-1"), stops)
    }

    @Test fun shutdownStopsSession() = runTest {
        val player = FakePlayer()
        val stops = mutableListOf<String>()
        val job = launch { PlaybackController(player, { _, _, _ -> }, { stops += it }).run(ready) }
        runCurrent()
        player.events.emit(PlayerEvent.Shutdown)
        job.join()
        assertEquals(listOf("sess-1"), stops)
    }

    @Test fun instantFailureDuringLoadDoesNotHang() = runTest {
        val player = FakePlayer(onLoad = { events.tryEmit(PlayerEvent.Ended(EndReason.ERROR, "unreachable")) })
        val stops = mutableListOf<String>()
        val job = launch { PlaybackController(player, { _, _, _ -> }, { stops += it }).run(ready) }
        runCurrent()
        assertTrue(job.isCompleted, "controller is still waiting for an end event it missed")
        assertEquals(listOf("sess-1"), stops)
    }

    @Test fun pauseChangeReportsImmediately() = runTest {
        val player = FakePlayer()
        val paused = mutableListOf<Boolean>()
        val job = launch { PlaybackController(player, { _, _, p -> paused += p }, { }).run(ready) }
        runCurrent()
        player.state.value = PlayerState(positionSeconds = 5.0, paused = true, loaded = true)
        runCurrent()
        assertEquals(listOf(true), paused)
        player.events.emit(PlayerEvent.Ended(EndReason.STOP, null)); job.join()
    }

    @Test fun cancellationStillStopsSession() = runTest {
        val player = FakePlayer()
        val stops = mutableListOf<String>()
        val job = launch { PlaybackController(player, { _, _, _ -> }, { stops += it }).run(ready) }
        runCurrent()
        job.cancel(); job.join()
        assertEquals(listOf("sess-1"), stops)
        assertTrue(player.stopped)
    }
}
