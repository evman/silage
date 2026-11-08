package dev.silage.app

import androidx.compose.ui.window.WindowPlacement

/**
 * Where the main window starts. Windows opens as a normal window (maximized, or as it was
 * left) unless [borderless] fullscreen is chosen, which fills the monitor with a plain
 * borderless window instead of AWT's exclusive mode; other systems start fullscreen.
 */
/** [linuxWindowed]: Settings → Interface → Window → Normal window on Linux (issue #7); Linux otherwise opens fullscreen. */
internal fun windowPlacement(borderless: Boolean, saved: SavedWindow?, osName: String = System.getProperty("os.name"), linuxWindowed: Boolean = false): WindowPlacement = when {
    // Linux: a normal window; fullscreen is asked of the window manager once it shows (LinuxFullscreen).
    !osName.startsWith("Windows") -> WindowPlacement.Maximized
    borderless -> WindowPlacement.Floating
    saved != null && !saved.maximized -> WindowPlacement.Floating
    else -> WindowPlacement.Maximized
}
