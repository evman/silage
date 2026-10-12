@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.silage.app.playback.hud

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image

/**
 * Owns the HUD's off-screen Compose scene. Closing is idempotent: the player
 * is closed more than once on the way out, and ImageComposeScene.close()
 * throws "ComposeScene is already closed" the second time.
 */
class HudSceneHost(width: Int, height: Int, density: Density, content: @Composable () -> Unit) : AutoCloseable {
    val scene = ImageComposeScene(width, height, density, content = content)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun render(nanoTime: Long): Image = scene.render(nanoTime)

    override fun close() {
        if (closed.compareAndSet(false, true)) scene.close()
    }
}
