package dev.silage.app

import androidx.compose.ui.window.WindowPlacement

/**
 * Where the main window starts. Windows opens as a normal window (maximized, or as it was
 * left) unless [borderless] fullscreen is chosen, which fills the monitor with a plain
 * borderless window instead of AWT's exclusive mode; other systems start fullscreen.
 */
internal fun windowPlacement(borderless: Boolean, saved: SavedWindow?, osName: String = System.getProperty("os.name")): WindowPlacement = when {
    !osName.startsWith("Windows") -> WindowPlacement.Fullscreen
    borderless -> WindowPlacement.Floating
    saved != null && !saved.maximized -> WindowPlacement.Floating
    else -> WindowPlacement.Maximized
}
