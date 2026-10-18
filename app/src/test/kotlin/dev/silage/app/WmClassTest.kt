package dev.silage.app

import kotlin.test.Test
import kotlin.test.assertEquals

class WmClassTest {
    /** The desktop entry's StartupWMClass=silage must match the window, or the taskbar shows no icon. */
    @Test fun theWindowClassIsSilage() {
        if (java.awt.GraphicsEnvironment.isHeadless() || !System.getProperty("os.name").startsWith("Linux")) return
        WmClass.set("silage")
        val f = Class.forName("sun.awt.X11.XToolkit").getDeclaredField("awtAppClassName").apply { isAccessible = true }
        assertEquals("silage", f.get(null))
    }
}
