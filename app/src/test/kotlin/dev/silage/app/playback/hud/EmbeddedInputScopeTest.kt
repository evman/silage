package dev.silage.app.playback.hud

import java.awt.Canvas
import java.awt.event.KeyEvent
import javax.swing.JFrame
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.*

class EmbeddedInputScopeTest {
    @Test fun shortcutsAreScopedToPlayerWindowAndDoNotStealTextOrModifiedKeys() {
        SwingUtilities.invokeAndWait {
            val player = JFrame(); val other = JFrame(); val canvas = Canvas(); val text = JTextField()
            try {
                player.add(canvas); other.add(text)
                fun event(c: java.awt.Component, mods: Int = 0) = KeyEvent(c, KeyEvent.KEY_PRESSED, 0, mods, KeyEvent.VK_F, 'f')
                assertTrue(acceptsPlayerKey(canvas, event(canvas)))
                assertFalse(acceptsPlayerKey(canvas, event(text)))
                assertFalse(acceptsPlayerKey(canvas, event(canvas, KeyEvent.CTRL_DOWN_MASK)))
                player.remove(canvas); player.add(text)
                assertFalse(acceptsPlayerKey(canvas, event(text)))
            } finally { player.dispose(); other.dispose() }
        }
    }

    /** Ctrl+Q quits Silage from the embedded player too, but not from another window. */
    @Test fun ctrlQInThePlayerWindowIsQuit() {
        SwingUtilities.invokeAndWait {
            val player = JFrame(); val other = JFrame(); val canvas = Canvas(); val text = JTextField()
            try {
                player.add(canvas); other.add(text)
                fun event(c: java.awt.Component, code: Int, mods: Int) = KeyEvent(c, KeyEvent.KEY_PRESSED, 0, mods, code, KeyEvent.CHAR_UNDEFINED)
                assertEquals(HudKey.Quit, playerKey(canvas, event(canvas, KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK)))
                assertEquals(HudKey.Back, playerKey(canvas, event(canvas, KeyEvent.VK_Q, 0)))
                assertEquals(null, playerKey(canvas, event(canvas, KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK)))
                assertEquals(null, playerKey(canvas, event(text, KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK)))
            } finally { player.dispose(); other.dispose() }
        }
    }
}
