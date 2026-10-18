package dev.silage.app.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsVideoOutputTest {
    @Test fun windowsEmbeddedUsesNativeContextAndUnsignedHandle() {
        val o = VideoOutput.Embedded(-1L).mpvOptions("Windows 11")
        assertEquals("d3d11", o["gpu-context"])
        assertEquals("4294967295", o["wid"])
        assertEquals("auto-safe", o["hwdec"])
        assertEquals("no", o["target-colorspace-hint"])
    }
    @Test fun windowsOwnWindowOmitsWaylandAndConnectorName() {
        val o = VideoOutput.OwnWindow("DP-1").mpvOptions("Windows 11")
        assertFalse("wayland-app-id" in o)
        assertFalse("fs-screen-name" in o)
        assertEquals("d3d11", o["gpu-context"])
    }
}
