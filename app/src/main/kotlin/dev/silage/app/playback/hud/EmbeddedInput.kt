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
 * canvas (mpv passes it through). The pointer over the video hides with the
 * HUD ([CursorAutoHide]). Returns a function that detaches it all.
 */
fun attachEmbeddedInput(canvas: Canvas, hud: PlayerHud): () -> Unit {
    val keys = KeyEventDispatcher { e ->
        if (!acceptsPlayerKey(canvas, e)) return@KeyEventDispatcher false
        val k = if (e.id == KeyEvent.KEY_PRESSED) HudKey.ofAwt(e.keyCode) else null
        k?.let(hud::key)
        k != null || (e.id != KeyEvent.KEY_PRESSED && HudKey.ofAwt(e.keyCode) != null)
    }
    val cursor = VideoCursor(canvas)
    val autoHide = CursorAutoHide()
    val cursorTimer = javax.swing.Timer(250) { cursor.hide(autoHide.hidden(System.currentTimeMillis(), hud.hudVisible)) }
    val mouse = object : MouseAdapter() {
        private fun at(e: MouseEvent, type: androidx.compose.ui.input.pointer.PointerEventType) {
            val c = e.component
            val held = (e.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK) != 0
            if (type == androidx.compose.ui.input.pointer.PointerEventType.Move) {
                // A move that goes nowhere (Windows sends one when the cursor changes) neither shows the pointer nor the HUD.
                if (!autoHide.movedTo(e.xOnScreen, e.yOnScreen) && !held) return
                cursor.hide(false)
            }
            if (c.width > 0 && c.height > 0) hud.pointerEvent(e.x.toFloat() / c.width, e.y.toFloat() / c.height, type, held)
        }
        override fun mouseMoved(e: MouseEvent) = at(e, androidx.compose.ui.input.pointer.PointerEventType.Move)
        override fun mouseDragged(e: MouseEvent) = at(e, androidx.compose.ui.input.pointer.PointerEventType.Move)
        override fun mousePressed(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) at(e, androidx.compose.ui.input.pointer.PointerEventType.Press) }
        override fun mouseReleased(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1) at(e, androidx.compose.ui.input.pointer.PointerEventType.Release) }
        override fun mouseClicked(e: MouseEvent) {
            val c = e.component
            if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2 && c.height > 0) hud.videoDoubleClick(e.y.toFloat() / c.height)
        }
    }
    SwingUtilities.invokeLater {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
        canvas.addMouseListener(mouse)
        canvas.addMouseMotionListener(mouse)
        cursorTimer.start()
    }
    return {
        SwingUtilities.invokeLater {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys)
            canvas.removeMouseListener(mouse)
            canvas.removeMouseMotionListener(mouse)
            cursorTimer.stop()
            cursor.restore()
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

/** Shows or hides the pointer over the video canvas (event thread only). */
private class VideoCursor(private val canvas: Canvas) {
    private var hidden = false

    fun hide(hide: Boolean) {
        if (hide == hidden) return
        val blank = Blank ?: return
        hidden = hide
        canvas.cursor = if (hide) blank else null
    }

    fun restore() {
        hidden = false
        canvas.cursor = null
    }

    companion object {
        /** A fully transparent 1×1 cursor; null where the toolkit can't make one. */
        val Blank: java.awt.Cursor? by lazy {
            runCatching {
                java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
                    java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "silage-blank")
            }.getOrNull()
        }
    }
}
