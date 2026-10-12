package dev.silage.player

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Keys bound with `keybind` reach the app as client messages. */
class MpvKeybindTest {
    private val player = MpvPlayer(mapOf("vo" to "null", "ao" to "null"))

    @AfterTest fun close() = player.close()

    @Test fun boundKeyArrivesAsClientMessage() = runBlocking {
        player.command("keybind", "SPACE", "script-message silage-key SPACE")
        val msg = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED, context = kotlinx.coroutines.Dispatchers.Default) { withTimeout(3_000) { player.events.filterIsInstance<PlayerEvent.ClientMessage>().first() } }
        Thread.sleep(100)
        player.command("keypress", "SPACE")
        assertEquals(listOf("silage-key", "SPACE"), msg.await().args)
    }
}
