package dev.silage.app.playback

import dev.silage.player.EndReason
import dev.silage.player.PlayerEvent
import dev.silage.player.PlayerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

private class ScriptedPlayer(private val log: MutableList<String>, private val end: PlayerEvent) : OpenedPlayer {
    override val state = MutableStateFlow(PlayerState())
    override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    override fun load(url: String, headers: Map<String, String>, startSeconds: Double?) { events.tryEmit(end) }
    override fun stop() { log += "player.stop" }
    private var closed = false
    override fun close() { if (!closed) { closed = true; log += "player.close" } }
}

class PlaybackSessionTest {
    private val ready = LaunchResult.Ready("sess-1", "http://nas/x", emptyMap(), null)
    private val log = mutableListOf<String>()

    private fun session(
        launch: LaunchResult = ready,
        open: (LaunchResult.Ready) -> OpenedPlayer = { ScriptedPlayer(log, PlayerEvent.Ended(EndReason.EOF, null)) },
    ) = PlaybackSession(
        launch = { launch },
        openPlayer = open,
        progress = { _, _, _ -> },
        stopSession = { log += "session.stop:$it" },
    )

    @Test fun playerOpensWithTheLaunchSoTheHudKnowsWhatIsPlaying() = runTest {
        var opened: LaunchResult.Ready? = null
        session(open = { opened = it; ScriptedPlayer(log, PlayerEvent.Ended(EndReason.EOF, null)) }).play("m")
        assertEquals(ready, opened)
    }

    @Test fun launchFailureIsReported() = runTest {
        assertEquals(PlayOutcome.Failed("nope"), session(launch = LaunchResult.Failed("nope")).play("m"))
        assertEquals(emptyList<String>(), log)
    }

    @Test fun playerThatWontStartIsReportedAndSessionStopped() = runTest {
        val outcome = session(open = { throw UnsatisfiedLinkError("libmpv.so not found") }).play("m")
        assertEquals(PlayOutcome.Failed("Couldn't start the video player: libmpv.so not found"), outcome)
        assertEquals(listOf("session.stop:sess-1"), log)
    }

    @Test fun playbackErrorIsReportedWithMpvReason() = runTest {
        val outcome = session(open = { ScriptedPlayer(log, PlayerEvent.Ended(EndReason.ERROR, "loading failed")) }).play("m")
        assertEquals(PlayOutcome.Failed("Playback failed: loading failed"), outcome)
    }

    @Test fun playingToTheEndIsCompletedAndStoppingEarlyIsFinished() = runTest {
        assertEquals(PlayOutcome.Completed, session().play("m"), "end of file: time for Up next")
        assertEquals(PlayOutcome.Finished, session(open = { ScriptedPlayer(log, PlayerEvent.Ended(EndReason.STOP, null)) }).play("m"))
    }

    @Test fun windowClosesBeforeServerStop() = runTest {
        assertEquals(PlayOutcome.Completed, session().play("m"))
        assertEquals(listOf("player.stop", "player.close", "session.stop:sess-1"), log)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun backPageIsPreparedBeforeWindowCloseAndDoesNotWaitForSlowServerCleanup() = runTest {
        val server = CompletableDeferred<Unit>()
        val job = launch {
            PlaybackSession(
                launch = { ready },
                openPlayer = { ScriptedPlayer(log, PlayerEvent.Ended(EndReason.STOP, null)) },
                progress = { _, _, _ -> },
                stopSession = { log += "session.stop"; server.await() },
                beforeClose = { assertEquals(PlayOutcome.Finished, it); log += "page.ready" },
            ).play("m")
        }
        runCurrent()
        assertEquals(listOf("page.ready", "player.stop", "player.close", "session.stop"), log)
        server.complete(Unit)
        job.join()
    }
}
