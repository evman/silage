package dev.silage.app.input

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import dev.silage.app.nav.BackInput
import dev.silage.app.playback.hud.HudKey
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/** What a controller button does in the player. */
fun playerKeyFor(button: GamepadButton): HudKey = when (button) {
    GamepadButton.A -> HudKey.Enter
    GamepadButton.B -> HudKey.Back
    GamepadButton.X, GamepadButton.Menu -> HudKey.PlayPause
    GamepadButton.Y -> HudKey.CycleSubtitles
    GamepadButton.LeftBumper -> HudKey.SkipBack
    GamepadButton.RightBumper -> HudKey.SkipForward
    GamepadButton.LeftTrigger -> HudKey.VolumeDown
    GamepadButton.RightTrigger -> HudKey.VolumeUp
    GamepadButton.View -> HudKey.Info
    GamepadButton.Up -> HudKey.Up
    GamepadButton.Down -> HudKey.Down
    GamepadButton.Left -> HudKey.Left
    GamepadButton.Right -> HudKey.Right
}

/** A key press the pages understand: its AWT code and modifiers. */
data class BrowseKey(val code: Int, val modifiers: Int = 0)

/** What a controller button does on the pages; null where it does nothing. */
fun browseKeyFor(button: GamepadButton): BrowseKey? = when (button) {
    GamepadButton.A -> BrowseKey(KeyEvent.VK_ENTER)
    GamepadButton.B -> BrowseKey(BackInput.AWT_BACK)
    GamepadButton.Y -> BrowseKey(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK)
    GamepadButton.Up -> BrowseKey(KeyEvent.VK_UP)
    GamepadButton.Down -> BrowseKey(KeyEvent.VK_DOWN)
    GamepadButton.Left -> BrowseKey(KeyEvent.VK_LEFT)
    GamepadButton.Right -> BrowseKey(KeyEvent.VK_RIGHT)
    else -> null
}

/**
 * Reads controllers for the life of the window and acts on their presses, only
 * while Silage is in front: to the open player, or otherwise as keys in the
 * window, so pages and dialogs need nothing of their own.
 */
object GamepadInput {
    /** The open player's keys; null on the pages. */
    @Volatile var player: ((HudKey) -> Unit)? = null

    fun install(window: Window, source: GamepadSource? = defaultSource()): AutoCloseable {
        source ?: return AutoCloseable {}
        val thread = Thread({ run(window, source) }, "silage-gamepad").apply { isDaemon = true; start() }
        return AutoCloseable { thread.interrupt() }
    }

    private fun defaultSource(): GamepadSource? = when {
        System.getProperty("os.name").orEmpty().startsWith("Windows") -> WindowsGamepads()
        System.getProperty("os.name").orEmpty().startsWith("Linux") -> LinuxGamepads()
        else -> null
    }

    private fun run(window: Window, source: GamepadSource) {
        val presses = GamepadPresses()
        val inFront = foregroundCheck(window)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val state = runCatching { source.poll() }.getOrNull()
                val pressed = presses.update(state ?: GamepadState(), System.currentTimeMillis())
                if (pressed.isNotEmpty() && inFront()) pressed.forEach { press(window, it) }
                // A connected pad is read quickly; with none, only look for one now and then.
                Thread.sleep(if (state != null) 16 else 500)
            }
        } catch (_: InterruptedException) {
            // Closed with the window.
        }
    }

    private fun press(window: Window, button: GamepadButton) {
        val hud = player
        if (hud != null) { hud(playerKeyFor(button)); return }
        if (TextEntry.button(button)) return
        val key = browseKeyFor(button) ?: return
        SwingUtilities.invokeLater { send(window, key) }
    }

    private fun send(window: Window, key: BrowseKey) {
        val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            ?.takeIf { SwingUtilities.getWindowAncestor(it) == window || it == window }
            ?: window.mostRecentFocusOwner ?: window
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        fun event(id: Int) = KeyEvent(target, id, System.currentTimeMillis(), key.modifiers, key.code, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_STANDARD)
        queue.postEvent(event(KeyEvent.KEY_PRESSED))
        queue.postEvent(event(KeyEvent.KEY_RELEASED))
    }

    private interface User32 : StdCallLibrary {
        fun GetForegroundWindow(): Pointer?
        fun GetWindowThreadProcessId(hwnd: Pointer, processId: IntByReference): Int
    }

    /**
     * Whether Silage has the foreground. On Windows this asks for the owning
     * process, which also covers the video's own native window; elsewhere it is
     * an active AWT window, or an open player (mpv's own window isn't one).
     */
    private fun foregroundCheck(window: Window): () -> Boolean {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return { player != null || window.isActive }
        val user32 = runCatching { Native.load("user32", User32::class.java) }.getOrNull() ?: return { window.isActive }
        val self = ProcessHandle.current().pid().toInt()
        return {
            val owner = IntByReference()
            user32.GetForegroundWindow()?.let { user32.GetWindowThreadProcessId(it, owner) }
            owner.value == self
        }
    }
}
