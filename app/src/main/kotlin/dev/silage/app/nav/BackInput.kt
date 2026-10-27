package dev.silage.app.nav

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * What counts as "Back": Escape and Backspace, a remote's Back key, the
 * keyboard's Browser Back and the mouse's Back button (issue #3).
 *
 * Browser Back and the mouse button have no key of their own in AWT, so
 * [install] turns them into one synthetic key, [AWT_BACK], which Compose
 * reads as [Key.Back] and the player as its Back key. Every Back handler
 * then only needs [isBack].
 */
object BackInput {
    /** The AWT key code Compose reads as [Key.Back] (it has no real AWT key). */
    val AWT_BACK: Int = Key.Back.nativeKeyCode
    val AWT_NAVIGATE_PREVIOUS: Int = Key.NavigatePrevious.nativeKeyCode

    /** Browser Back's raw code: Windows' VK_BROWSER_BACK (0xA6), and XF86Back's X11 key code (evdev 158 + 8). */
    internal const val RAW_BROWSER_BACK = 166L

    private const val MOUSE_BACK_BUTTON = 4

    fun isBackKey(key: Key): Boolean = key == Key.Escape || key == Key.Backspace || key == Key.Back || key == Key.NavigatePrevious

    /** A Back key going down. */
    fun isBack(e: KeyEvent): Boolean = e.type == KeyEventType.KeyDown && isBackKey(e.key)

    fun isMouseBack(id: Int, button: Int): Boolean = id == MouseEvent.MOUSE_PRESSED && button == MOUSE_BACK_BUTTON

    /** A key AWT knows no code for, whose raw code is Browser Back's. */
    fun isBrowserBack(keyCode: Int, rawCode: Long?): Boolean = keyCode == AwtKeyEvent.VK_UNDEFINED && rawCode == RAW_BROWSER_BACK

    /** The raw (platform) key code, which AWT only shows in [AwtKeyEvent.paramString]. */
    fun rawCodeOf(paramString: String): Long? = Regex("""(?:^|,)rawCode=(\d+)""").find(paramString)?.groupValues?.get(1)?.toLongOrNull()

    fun backKeyEvent(source: Component, id: Int): AwtKeyEvent =
        AwtKeyEvent(source, id, System.currentTimeMillis(), 0, AWT_BACK, AwtKeyEvent.CHAR_UNDEFINED, AwtKeyEvent.KEY_LOCATION_STANDARD)

    /** Presses the synthetic Back key in [window], where the keyboard focus is. Call on the event thread. */
    fun send(window: Window) {
        val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            ?.takeIf { SwingUtilities.getWindowAncestor(it) == window || it == window }
            ?: window.mostRecentFocusOwner ?: window
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        queue.postEvent(backKeyEvent(target, AwtKeyEvent.KEY_PRESSED))
        queue.postEvent(backKeyEvent(target, AwtKeyEvent.KEY_RELEASED))
    }

    /**
     * Turns Browser Back and the mouse's Back button into the Back key for
     * [window], everywhere (pages, dialogs, the player); on Windows also
     * WM_APPCOMMAND's Browser Back. Returns what removes it all.
     */
    fun install(window: Window): AutoCloseable {
        val sources = BackSources()
        val mouse = AWTEventListener { e ->
            if (e is MouseEvent && isMouseBack(e.id, e.button) && SwingUtilities.getWindowAncestor(e.component).let { it == window || e.component == window }) {
                SwingUtilities.invokeLater { send(window) }
            }
        }
        val keys = KeyEventDispatcher { e ->
            if (e.keyCode != AwtKeyEvent.VK_UNDEFINED || e.id == AwtKeyEvent.KEY_TYPED) return@KeyEventDispatcher false
            if (!isBrowserBack(e.keyCode, rawCodeOf(e.paramString()))) return@KeyEventDispatcher false
            if (e.id == AwtKeyEvent.KEY_PRESSED && sources.accept(BackSources.Source.Key)) send(window)
            e.consume()
            true
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(mouse, AWTEvent.MOUSE_EVENT_MASK)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
        val appCommands = dev.silage.app.WindowsAppCommands.install(window) {
            SwingUtilities.invokeLater { if (sources.accept(BackSources.Source.AppCommand)) send(window) }
        }
        return AutoCloseable {
            runCatching { appCommands.close() }
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys)
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouse)
        }
    }
}

/**
 * One Browser Back press can reach Silage twice on Windows, as a key and as
 * WM_APPCOMMAND: a Back from the other source just after one is the same
 * press. Repeats from one source all count. Used on the event thread.
 */
class BackSources(private val windowMs: Long = 400) {
    enum class Source { Key, AppCommand }

    private var last: Source? = null
    private var lastAt = 0L

    fun accept(source: Source, now: Long = System.currentTimeMillis()): Boolean {
        val previous = last
        if (previous != null && previous != source && now - lastAt < windowMs) return false
        last = source
        lastAt = now
        return true
    }
}
