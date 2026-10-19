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
            // Win32 monitor coordinates match SetWindowPos even with Windows DPI scaling.
            val monitor = u.MonitorFromWindow(hwnd, 2) // MONITOR_DEFAULTTONEAREST
            val info = Memory(40).also { it.clear(); it.setInt(0, 40) } // MONITORINFO
            check(u.GetMonitorInfoW(monitor, info))
            val bounds = Rectangle(info.getInt(4), info.getInt(8),
                info.getInt(12) - info.getInt(4), info.getInt(16) - info.getInt(8))
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
            placement?.let { check(u.SetWindowPlacement(hwnd,it)) }
            placement = null
        } else { frame.bounds = saved.bounds; frame.extendedState = saved.extendedState }
        active = false
    }
    override fun close() = leave()
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
