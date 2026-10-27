package dev.silage.app

import javax.swing.SwingUtilities

/**
 * Quits Silage the way closing its window does (saving the window's place first): Main sets
 * [handler] once the window exists, and code outside the UI, such as installing an update,
 * calls [quit] from any thread.
 */
class AppExit {
    @Volatile var handler: (() -> Unit)? = null

    fun quit() {
        val close = handler ?: return kotlin.system.exitProcess(0)
        SwingUtilities.invokeLater(close)
    }
}
