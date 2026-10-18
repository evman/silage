package dev.silage.app.notifications

import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * Windows notifications ("toasts") for new inbox items, shown through a tray
 * icon that exists only while a toast is up, so Silage leaves nothing in the
 * notification area. Clicking the toast calls [onClick]. Never throws.
 */
class DesktopToast(private val onClick: () -> Unit) {
    private var icon: TrayIcon? = null
    private var removal: java.util.TimerTask? = null
    private val timer by lazy { Timer("silage-toast", true) }

    val supported: Boolean get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    @Synchronized
    fun show(title: String, body: String) {
        if (!supported) return
        runCatching {
            val tray = SystemTray.getSystemTray()
            val current = icon ?: TrayIcon(trayImage(), dev.silage.app.BuildInfo.NAME).apply {
                isImageAutoSize = true
                // A click on the toast (or the icon) brings Silage forward.
                addActionListener { onClick() }
                tray.add(this)
            }.also { icon = it }
            current.displayMessage(title, body, TrayIcon.MessageType.NONE)
            removal?.cancel()
            removal = timer.schedule(15_000) { hide() }
        }
    }

    @Synchronized
    fun hide() {
        val current = icon ?: return
        icon = null
        runCatching { SystemTray.getSystemTray().remove(current) }
    }
}

/** The window's own icon (the Silage logo), else a plain dark tile. */
private fun trayImage(): java.awt.Image =
    java.awt.Window.getWindows().firstNotNullOfOrNull { it.iconImages.firstOrNull() }
        ?: java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = java.awt.Color(0x0B, 0x0C, 0x0F)
            g.fillRoundRect(0, 0, 32, 32, 8, 8)
            g.dispose()
        }
