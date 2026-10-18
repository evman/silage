package dev.silage.app

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import dev.silage.app.playback.WindowsWindowApi

/** Native Windows window chrome. Every call is a no-op elsewhere and never throws. */
internal object WindowsChrome {
    internal interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm by lazy { Native.load("dwmapi", Dwm::class.java) }

    /** DWMWA_USE_IMMERSIVE_DARK_MODE: 20 from Windows 10 20H1, 19 on 1809–1909. */
    private val darkModeAttributes = intArrayOf(20, 19)

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    /**
     * Gives [window] the dark title bar Windows uses for dark apps, instead of
     * the default white one over Silage's black UI. Returns whether it applied.
     */
    fun useDarkTitleBar(window: java.awt.Window, osName: String = System.getProperty("os.name").orEmpty()): Boolean {
        if (!osName.startsWith("Windows") || !window.isDisplayable) return false
        return runCatching {
            val hwnd = Native.getComponentPointer(window) ?: return false
            val on = IntByReference(1)
            val applied = darkModeAttributes.any { attribute -> dwm.DwmSetWindowAttribute(hwnd, attribute, on, 4) == 0 }
            if (applied) {
                // Repaint the frame now rather than on the next resize or focus change.
                WindowsWindowApi.instance.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                    SWP_NOSIZE or SWP_NOMOVE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
            }
            applied
        }.getOrDefault(false)
    }
}
