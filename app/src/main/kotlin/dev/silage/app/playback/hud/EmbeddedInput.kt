package dev.silage.app.playback.hud

import java.awt.Canvas
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Feeds the Silage window's input to the HUD while video is embedded: keys
 * window-wide (whichever component has focus), and the mouse from the video
 * canvas (mpv passes it through). Returns a function that detaches it all.
 */
fun attachEmbeddedInput(canvas: Canvas, hud: PlayerHud): () -> Unit {
    val keys = KeyEventDispatcher { e ->
        if (!acceptsPlayerKey(canvas, e)) return@KeyEventDispatcher false
        val k = if (e.id == KeyEvent.KEY_PRESSED) HudKey.ofAwt(e.keyCode) else null
        k?.let(hud::key)
        k != null || (e.id != KeyEvent.KEY_PRESSED && HudKey.ofAwt(e.keyCode) != null)
    }
    val mouse = object : MouseAdapter() {
        private fun at(e: MouseEvent, press: Boolean) {
            val c = e.component
            if (c.width > 0 && c.height > 0) hud.pointerAt(e.x.toFloat() / c.width, e.y.toFloat() / c.height, press)
        }
        override fun mouseMoved(e: MouseEvent) = at(e, press = false)
        override fun mouseDragged(e: MouseEvent) = at(e, press = false)
        override fun mousePressed(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) at(e, press = true) }
    }
    SwingUtilities.invokeLater {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
        canvas.addMouseListener(mouse)
        canvas.addMouseMotionListener(mouse)
    }
    return {
        SwingUtilities.invokeLater {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys)
            canvas.removeMouseListener(mouse)
            canvas.removeMouseMotionListener(mouse)
        }
    }
}

internal fun acceptsPlayerKey(canvas: Canvas, event: KeyEvent): Boolean {
    val owner = SwingUtilities.getWindowAncestor(canvas) ?: return false
    if (SwingUtilities.getWindowAncestor(event.component) != owner && event.component != owner) return false
    if (event.isAltDown || event.isControlDown || event.isMetaDown) return false
    if (event.component is javax.swing.text.JTextComponent) return false
    return true
}
