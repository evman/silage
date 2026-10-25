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

/**
 * Redraws the window at the right size after it moves to a monitor with a different scale.
 *
 * Compose draws into a Skia layer that resizes itself when the Java runtime reports a scale
 * change. The JetBrains Runtime reports one; the runtime Silage ships with only reports a new
 * screen, so a window moved between monitors scaled differently (say 150% and 100%) kept
 * drawing at the old size, leaving the rest of the window white. On a screen change this
 * nudges each Skia layer's size, which makes it measure the window and its scale again.
 */
internal object ScreenScaleRefresh {
    /** Skia layers anywhere under [root]: matched by class name, as skiko is not on the app's API. */
    internal fun skiaLayers(root: java.awt.Component): List<java.awt.Component> = buildList {
        fun walk(c: java.awt.Component) {
            if (generateSequence<Class<*>>(c.javaClass) { it.superclass }.any { it.simpleName == "SkiaLayer" }) add(c)
            (c as? java.awt.Container)?.components?.forEach(::walk)
        }
        walk(root)
    }

    private fun refresh(window: java.awt.Window) {
        if (!window.isShowing) return
        window.invalidate()
        window.validate()
        skiaLayers(window).forEach { layer ->
            val b = layer.bounds
            if (b.width > 1 && b.height > 1) {
                layer.setBounds(b.x, b.y, b.width, b.height - 1)
                layer.setBounds(b.x, b.y, b.width, b.height)
            }
            layer.revalidate()
            layer.repaint()
        }
        window.repaint()
    }

    /** Watches [window] for screen changes until closed. */
    fun follow(window: java.awt.Window): AutoCloseable {
        // Windows resizes the window for the new scale just after the screen change: refresh then, and once more after.
        val later = javax.swing.Timer(300) { runCatching { refresh(window) } }.apply { isRepeats = false }
        val listener = java.beans.PropertyChangeListener {
            javax.swing.SwingUtilities.invokeLater { runCatching { refresh(window) } }
            later.restart()
        }
        window.addPropertyChangeListener("graphicsConfiguration", listener)
        return AutoCloseable {
            later.stop()
            window.removePropertyChangeListener("graphicsConfiguration", listener)
        }
    }
}
