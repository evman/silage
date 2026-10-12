package dev.silage.app.playback

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.sun.jna.Native
import java.awt.Canvas

/**
 * A heavyweight, black AWT canvas filling its slot. [onReady] fires once the
 * canvas has a native window, with its X window id (null when the toolkit
 * has no X window to give, so the caller can fall back).
 */
@Composable
fun VideoSurface(onReady: (canvas: Canvas, wid: Long?) -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    SwingPanel(
        background = Color.Black,
        modifier = modifier,
        factory = {
            object : Canvas() {
                override fun addNotify() {
                    super.addNotify()
                    val wid = runCatching { Native.getComponentID(this) }.getOrNull()?.takeIf { it != 0L }
                    onReady(this, wid)
                }
            }.apply {
                background = java.awt.Color.BLACK
                isFocusable = true
            }
        },
    )
}
