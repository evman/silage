package dev.silage.player

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpvPlayerTest {
    // Headless: no window, no audio device.
    private val player = MpvPlayer(mapOf("vo" to "null", "ao" to "null"))

    @AfterTest fun tearDown() = player.close()

    /**
     * The test clip, or null when ffmpeg isn't installed to make it (a plain
     * Windows PC): those tests then return early, like the opt-in smoke tests.
     */
    private fun clip(): String? = if (TestClips.available) TestClips.fiveSeconds.toString() else null

    /** Subscribes before [action] so fast events (a 5 s local clip) can't be missed. */
    private suspend inline fun <reified E : PlayerEvent> awaitEvent(crossinline action: () -> Unit): E = kotlinx.coroutines.coroutineScope {
        val event = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) { player.events.filterIsInstance<E>().first() }
        }
        action()
        event.await()
    }

    @Test fun loadsAndReportsDuration() = runBlocking<Unit> {
        val file = clip() ?: return@runBlocking
        awaitEvent<PlayerEvent.FileLoaded> { player.load(file) }
        withTimeout(5_000) { player.state.first { it.durationSeconds > 4.5 } }
        assertTrue(player.state.value.loaded)
    }

    @Test fun seeksAbsolute() = runBlocking<Unit> {
        val file = clip() ?: return@runBlocking
        awaitEvent<PlayerEvent.FileLoaded> { player.load(file) }
        player.setPaused(true)
        player.seekAbsolute(3.0)
        withTimeout(5_000) { player.state.first { it.positionSeconds in 2.9..3.5 } }
    }

    @Test fun startPositionIsHonored() = runBlocking<Unit> {
        val file = clip() ?: return@runBlocking
        player.setPaused(true)
        player.load(file, startSeconds = 2.0)
        withTimeout(5_000) { player.state.first { it.loaded && it.positionSeconds in 1.9..2.5 } }
    }

    @Test fun playsToEndOfFile() = runBlocking<Unit> {
        val file = clip() ?: return@runBlocking
        val ended = awaitEvent<PlayerEvent.Ended> { player.load(file) }
        assertEquals(EndReason.EOF, ended.reason)
    }

    @Test fun missingFileReportsError() = runBlocking<Unit> {
        val ended = awaitEvent<PlayerEvent.Ended> { player.load("/definitely/not/here.mkv") }
        assertEquals(EndReason.ERROR, ended.reason)
        assertNotNull(ended.error)
    }

    @Test fun headersWithCommasSurvive() {
        val file = clip() ?: return
        player.load(file, headers = mapOf("Authorization" to "Bearer a,b:c"))
        val fields = player.getProperty("http-header-fields")
        assertNotNull(fields)
        assertTrue(fields.contains("Bearer a,b:c"), "got: $fields")
    }

    @Test fun scriptMessagesArriveAsClientMessages() = runBlocking<Unit> {
        val msg = async {
            withTimeout(5_000) { player.events.filterIsInstance<PlayerEvent.ClientMessage>().first() }
        }
        delay(100)
        player.command("script-message", "silage-key", "ENTER")
        assertEquals(listOf("silage-key", "ENTER"), msg.await().args)
    }

    @Test fun quitEmitsShutdown() = runBlocking<Unit> {
        val shut = async {
            withTimeout(5_000) { player.events.filterIsInstance<PlayerEvent.Shutdown>().first() }
        }
        delay(100)
        player.command("quit")
        shut.await()
        Unit
    }

    @Test fun closeIsIdempotent() {
        player.close(); player.close()
    }
}
