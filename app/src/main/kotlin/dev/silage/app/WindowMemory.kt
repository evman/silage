package dev.silage.app

import kotlinx.serialization.Serializable
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/** The main window's last normal (not maximized) bounds, in window units, plus whether it was maximized. */
@Serializable
data class SavedWindow(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

/** Smallest bounds worth restoring; anything smaller falls back to the default (maximized). */
internal const val MinRestoreWidth = 480
internal const val MinRestoreHeight = 320

/**
 * [saved] if it can be restored on one of [screens]: a sensible size, and enough
 * of its title bar on a screen to grab it (a monitor may have been unplugged).
 */
internal fun restorableWindow(saved: SavedWindow?, screens: List<Rectangle>): SavedWindow? {
    if (saved == null || saved.width < MinRestoreWidth || saved.height < MinRestoreHeight) return null
    val titleBar = Rectangle(saved.x, saved.y, saved.width, 40)
    return saved.takeIf { screens.any { screen -> screen.intersection(titleBar).let { !it.isEmpty && it.width >= 120 && it.height >= 24 } } }
}

internal fun screenBounds(): List<Rectangle> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
}.getOrDefault(emptyList())

/**
 * Tracks the main window so it reopens where it was left. Updates are ignored
 * while playback is fullscreen, which moves the window to cover the monitor.
 */
internal class WindowMemory(initial: SavedWindow?, private val store: (SavedWindow) -> Unit) {
    private var bounds: Rectangle? = initial?.let { Rectangle(it.x, it.y, it.width, it.height) }
    private var maximized = initial?.maximized ?: true

    fun update(maximized: Boolean, floatingBounds: Rectangle?, playbackFullscreen: Boolean) {
        if (playbackFullscreen) return
        this.maximized = maximized
        if (!maximized && floatingBounds != null) bounds = Rectangle(floatingBounds)
    }

    /** What would be saved now, or null when no normal bounds are known yet. */
    fun snapshot(): SavedWindow? = bounds?.let { SavedWindow(it.x, it.y, it.width, it.height, maximized) }

    fun save() {
        snapshot()?.let { runCatching { store(it) } }
    }
}
