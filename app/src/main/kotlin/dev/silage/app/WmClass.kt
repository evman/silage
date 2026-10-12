package dev.silage.app

/**
 * Under X11/XWayland the AWT window class defaults to the main class name
 * ("dev-silage-app-MainKt"), so the desktop can't match the window to its
 * launcher (StartupWMClass=silage). Needs
 * --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED; a no-op elsewhere.
 */
object WmClass {
    fun set(name: String) {
        runCatching {
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(toolkit, name)
        }
    }
}
