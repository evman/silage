package dev.silage.app.playback

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EmbeddedVideoTest {
    @Test fun embeddedPlayerDrawsIntoTheAppWindowInsteadOfItsOwn() {
        val opts = EmbeddedVideo.mpvOptions(wid = 0x3a00007L)
        assertEquals("60817415", opts["wid"])
        assertFalse("fullscreen" in opts, "an embedded player must not open its own fullscreen window")
        // The app window owns the keyboard; mpv's child window must not steal it.
        assertEquals("no", opts["input-vo-keyboard"])
        assertEquals("gpu-next", opts["vo"])
        assertEquals("x11vk", opts["gpu-context"])
    }

    @Test fun withoutAWindowHandleThePlayerFallsBackToItsOwnFullscreenWindow() {
        val opts = EmbeddedVideo.mpvOptions(wid = null)
        assertNull(opts["wid"])
        assertEquals("yes", opts["fullscreen"])
    }

    @Test fun keysMapToPlayerCommands() {
        assertContentEquals(arrayOf("stop"), EmbeddedVideo.commandFor(KeyEvent.VK_ESCAPE))
        assertContentEquals(arrayOf("stop"), EmbeddedVideo.commandFor(KeyEvent.VK_BACK_SPACE))
        assertContentEquals(arrayOf("cycle", "pause"), EmbeddedVideo.commandFor(KeyEvent.VK_SPACE))
        assertContentEquals(arrayOf("seek", "-10"), EmbeddedVideo.commandFor(KeyEvent.VK_LEFT))
        assertContentEquals(arrayOf("seek", "10"), EmbeddedVideo.commandFor(KeyEvent.VK_RIGHT))
        assertNull(EmbeddedVideo.commandFor(KeyEvent.VK_A))
    }
}
