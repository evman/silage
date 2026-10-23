package dev.silage.app.playback

import androidx.compose.runtime.staticCompositionLocalOf
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.awt.Rectangle
import javax.swing.SwingUtilities

val LocalPlaybackFullscreen = staticCompositionLocalOf<PlaybackFullscreen?> { null }
data class WindowSnapshot(val bounds: Rectangle, val extendedState: Int, val style: Int)
class FullscreenState {
    private var saved: WindowSnapshot? = null
    val active get() = saved != null
    fun enter(snapshot: WindowSnapshot): WindowSnapshot = saved ?: snapshot.copy(bounds = Rectangle(snapshot.bounds)).also { saved = it }
    fun exit(): WindowSnapshot? = saved.also { saved = null }
}

/** Windows borderless fullscreen: keep HWND and child video alive, never AWT exclusive mode. */
class PlaybackFullscreen(private val frame: Frame) : AutoCloseable {
    private val state = FullscreenState()
    private var placement: Memory? = null
    @Volatile var active = false; private set
    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
    fun toggle() = onEdt { if (active) leaveNow() else enterNow() }
    fun enter() = onEdt { enterNow() }
    fun leave() = onEdt { leaveNow() }
    private fun enterNow() {
        if (active || !frame.isShowing) return
        if (System.getProperty("os.name").startsWith("Windows")) {
            val u = WindowsWindowApi.instance
            val hwnd = Native.getComponentPointer(frame)
            val bounds = WindowsMonitors.nearestBounds(hwnd)
            val style = u.GetWindowLongW(hwnd, -16)
            placement = Memory(44).also { it.clear(); it.setInt(0,44); check(u.GetWindowPlacement(hwnd,it)) }
            state.enter(WindowSnapshot(frame.bounds, frame.extendedState, style))
            u.ShowWindow(hwnd, 9) // remove maximization constraints before applying monitor bounds
            u.SetWindowLongW(hwnd, -16, style and (0x00CF0000).inv())
            check(u.SetWindowPos(hwnd, null, bounds.x, bounds.y, bounds.width, bounds.height, 0x0020 or 0x0004))
        } else {
            state.enter(WindowSnapshot(frame.bounds, frame.extendedState, 0))
            frame.extendedState = Frame.NORMAL
            frame.bounds = frame.graphicsConfiguration.bounds
        }
        active = true
    }
    private fun leaveNow() {
        val saved = state.exit() ?: return
        if (System.getProperty("os.name").startsWith("Windows")) {
            val u = WindowsWindowApi.instance
            val hwnd = Native.getComponentPointer(frame)
            u.SetWindowLongW(hwnd, -16, saved.style)
            check(u.SetWindowPos(hwnd, null, 0,0,0,0, 0x0020 or 0x0001 or 0x0002 or 0x0004))
            if (WindowsMonitors.isBorderless(saved.style)) {
                // Silage's own borderless window: stay on the monitor it is on now. The saved
                // placement may belong to the monitor playback started on (Win+Shift+Arrow since).
                WindowsMonitors.fill(hwnd)
            } else {
                placement?.let { check(u.SetWindowPlacement(hwnd,it)) }
            }
            placement = null
        } else { frame.bounds = saved.bounds; frame.extendedState = saved.extendedState }
        active = false
    }
    override fun close() = leave()
}
/**
 * Keeps Silage's borderless window filling exactly one monitor. Moving it to a
 * monitor with another resolution or scaling (Win+Shift+Arrow, display changes)
 * otherwise leaves it at the old monitor's size, cut off or with the interface
 * scaled for the wrong screen.
 */
internal object WindowsMonitors {
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000

    fun isBorderless(style: Int) = (style and (WS_CAPTION or WS_THICKFRAME)) == 0

    /** The work-independent bounds of the monitor nearest [hwnd], in physical pixels. */
    fun nearestBounds(hwnd: Pointer): Rectangle {
        val u = WindowsWindowApi.instance
        // Win32 monitor coordinates match SetWindowPos even with Windows DPI scaling.
        val monitor = u.MonitorFromWindow(hwnd, 2) // MONITOR_DEFAULTTONEAREST
        val info = Memory(40).also { it.clear(); it.setInt(0, 40) } // MONITORINFO
        check(u.GetMonitorInfoW(monitor, info))
        return Rectangle(info.getInt(4), info.getInt(8), info.getInt(12) - info.getInt(4), info.getInt(16) - info.getInt(8))
    }

    fun windowBounds(hwnd: Pointer): Rectangle {
        val r = Memory(16).also { it.clear() }
        check(WindowsWindowApi.instance.GetWindowRect(hwnd, r))
        return Rectangle(r.getInt(0), r.getInt(4), r.getInt(8) - r.getInt(0), r.getInt(12) - r.getInt(4))
    }

    /** Sizes [hwnd] to its nearest monitor; true when it had to move. */
    fun fill(hwnd: Pointer): Boolean {
        val target = nearestBounds(hwnd)
        if (windowBounds(hwnd) == target) return false
        return WindowsWindowApi.instance.SetWindowPos(hwnd, null, target.x, target.y, target.width, target.height, 0x0020 or 0x0004 or 0x0010)
    }

    /**
     * Refits [frame] whenever it moves, resizes or changes screen (DPI), once the
     * move settles. Only while the window is borderless (it always is on Windows).
     */
    fun followMonitor(frame: Frame): AutoCloseable {
        val timer = javax.swing.Timer(250) {
            runCatching {
                if (!frame.isShowing || (frame.extendedState and Frame.ICONIFIED) != 0) return@runCatching
                val hwnd = Native.getComponentPointer(frame) ?: return@runCatching
                if (isBorderless(WindowsWindowApi.instance.GetWindowLongW(hwnd, -16))) fill(hwnd)
            }
        }.apply { isRepeats = false }
        val listener = object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) = timer.restart()
            override fun componentResized(e: java.awt.event.ComponentEvent) = timer.restart()
        }
        val screenChange = java.beans.PropertyChangeListener { timer.restart() }
        frame.addComponentListener(listener)
        frame.addPropertyChangeListener("graphicsConfiguration", screenChange)
        return AutoCloseable {
            timer.stop()
            frame.removeComponentListener(listener)
            frame.removePropertyChangeListener("graphicsConfiguration", screenChange)
        }
    }
}

internal interface WindowsWindowApi : StdCallLibrary {
    fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer
    fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean
    fun GetWindowLongW(hwnd: Pointer, index: Int): Int
    fun SetWindowLongW(hwnd: Pointer, index: Int, value: Int): Int
    fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, w: Int, h: Int, flags: Int): Boolean
    fun GetWindowPlacement(hwnd: Pointer, placement: Pointer): Boolean
    fun SetWindowPlacement(hwnd: Pointer, placement: Pointer): Boolean
    fun ShowWindow(hwnd: Pointer, command: Int): Boolean
    fun GetWindowRect(hwnd: Pointer, rect: Pointer): Boolean
    companion object { val instance: WindowsWindowApi by lazy { Native.load("user32", WindowsWindowApi::class.java) } }
}
