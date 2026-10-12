package dev.silage.app.playback

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.sun.jna.Native
import java.awt.Canvas

/**
 * The window's real pixel density, before Silage's uniform 1080p layout
 * scale. Native (Swing) panels must be sized with it: SwingPanel divides
 * layout pixels by LocalDensity to get AWT size, so under the 2× layout scale
 * on a 4K screen a full-size panel came out at half width and height.
 */
val LocalWindowDensity = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.unit.Density?> { null }

/**
 * A heavyweight, black AWT canvas filling its slot. [onReady] fires once the
 * canvas has a native window, with its X window id (null when the toolkit
 * has no X window to give, so the caller can fall back).
 */
@Composable
fun VideoSurface(onReady: (canvas: Canvas, wid: Long?) -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val real = LocalWindowDensity.current ?: androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides real) { SwingPanel(
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
    ) }
}
