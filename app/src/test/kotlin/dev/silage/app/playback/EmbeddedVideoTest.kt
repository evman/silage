package dev.silage.app.playback

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class EmbeddedVideoTest {
    @Test fun keysMapToPlayerCommands() {
        assertContentEquals(arrayOf("stop"), EmbeddedVideo.commandFor(KeyEvent.VK_ESCAPE))
        assertContentEquals(arrayOf("stop"), EmbeddedVideo.commandFor(KeyEvent.VK_BACK_SPACE))
        assertContentEquals(arrayOf("cycle", "pause"), EmbeddedVideo.commandFor(KeyEvent.VK_SPACE))
        assertContentEquals(arrayOf("seek", "-10"), EmbeddedVideo.commandFor(KeyEvent.VK_LEFT))
        assertContentEquals(arrayOf("seek", "10"), EmbeddedVideo.commandFor(KeyEvent.VK_RIGHT))
        assertNull(EmbeddedVideo.commandFor(KeyEvent.VK_A))
    }
}
