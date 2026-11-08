package dev.silage.app.playback

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import java.awt.Frame

/** An EWMH `_NET_WM_STATE` request's data: action (1 add, 0 remove), the property, none, source (1 = application). */
internal fun ewmhFullscreenData(on: Boolean, fullscreenAtom: Long): LongArray = longArrayOf(if (on) 1 else 0, fullscreenAtom, 0, 1)

/**
 * The Silage window's fullscreen on Linux. Compose's Fullscreen placement is AWT's exclusive
 * fullscreen (GraphicsDevice.setFullScreenWindow), which KDE doesn't let a window leave again
 * (F11 did nothing). Instead the window stays a normal one and the window manager is asked for
 * fullscreen with the EWMH request browsers and video players use, so KWin enters and leaves it
 * as with its own shortcut. [request] returns false when it couldn't be sent (no X11); [fallback]
 * then uses Compose's placement.
 */
class LinuxFullscreen(private val request: (Boolean) -> Boolean, private val fallback: (Boolean) -> Unit) : LinuxPlacement {
    @Volatile private var on = false
    override var fullscreen: Boolean
        get() = on
        set(value) {
            if (!request(value)) fallback(value)
            on = value
        }
}

internal object X11Fullscreen {
    /** Asks the window manager to put [frame] in or out of fullscreen; false when it can't be asked. */
    fun request(frame: Frame, on: Boolean): Boolean = runCatching {
        if (!frame.isDisplayable) return false
        val x = X11.INSTANCE
        val display = x.XOpenDisplay(null) ?: return false
        try {
            val event = X11.XEvent()
            event.setType(X11.XClientMessageEvent::class.java)
            event.xclient.apply {
                type = X11.ClientMessage
                send_event = 1
                this.display = display
                window = X11.Window(Native.getWindowID(frame))
                message_type = x.XInternAtom(display, "_NET_WM_STATE", false)
                format = 32
                data.setType(Array<NativeLong>::class.java)
                val atom = x.XInternAtom(display, "_NET_WM_STATE_FULLSCREEN", false).toLong()
                ewmhFullscreenData(on, atom).forEachIndexed { i, v -> data.l[i] = NativeLong(v) }
            }
            val mask = NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong())
            x.XSendEvent(display, x.XDefaultRootWindow(display), 0, mask, event) != 0
        } finally {
            x.XFlush(display)
            x.XCloseDisplay(display)
        }
    }.getOrDefault(false)
}
