package dev.silage.app.playback

import java.awt.event.KeyEvent

/** Keys pressed on the Silage-side video surface. */
object EmbeddedVideo {
    /** The mpv command for a key pressed on the video surface, or null to ignore it. */
    fun commandFor(keyCode: Int): Array<String>? = when (keyCode) {
        KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_Q -> arrayOf("stop")
        KeyEvent.VK_SPACE, KeyEvent.VK_K -> arrayOf("cycle", "pause")
        KeyEvent.VK_LEFT -> arrayOf("seek", "-10")
        KeyEvent.VK_RIGHT -> arrayOf("seek", "10")
        else -> null
    }
}
