package dev.silage.app.playback

import java.awt.event.KeyEvent

/**
 * How mpv is configured to draw inside the Silage window: it gets the X
 * window id of a heavyweight canvas (`wid`) and renders as a child of it.
 * Without a handle (a non-X11 AWT toolkit) it falls back to its own
 * fullscreen window.
 */
object EmbeddedVideo {
    private val video = mapOf(
        "vo" to "gpu-next",
        "hwdec" to "auto-safe",
        "target-colorspace-hint" to "yes",
        "force-window" to "yes",
    )

    fun mpvOptions(wid: Long?): Map<String, String> =
        if (wid != null) {
            // wid is an X11 window: skip the Wayland contexts mpv would try first.
            video + mapOf("wid" to wid.toString(), "gpu-context" to "x11vk", "input-vo-keyboard" to "no")
        } else {
            video + mapOf("fullscreen" to "yes")
        }

    /** The mpv command for a key pressed on the video surface, or null to ignore it. */
    fun commandFor(keyCode: Int): Array<String>? = when (keyCode) {
        KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_Q -> arrayOf("stop")
        KeyEvent.VK_SPACE, KeyEvent.VK_K -> arrayOf("cycle", "pause")
        KeyEvent.VK_LEFT -> arrayOf("seek", "-10")
        KeyEvent.VK_RIGHT -> arrayOf("seek", "10")
        else -> null
    }
}
