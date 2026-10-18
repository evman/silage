package dev.silage.app.playback

import kotlin.test.*

class VideoSettingsTest {
    @Test fun rejectsNativeReadbackMismatchInsteadOfShowingUnappliedValues() {
        val session = VideoSession()
        val defaults = VideoSettings().properties()
        assertFalse(session.update(VideoSettings(gamma = 20), { _, _ -> 0 }, { defaults[it] }))
        assertEquals(VideoSettings(), session.settings)
        assertTrue(session.error!!.contains("gamma"))
    }
    @Test fun sessionReappliesAdjustmentsToReplacementPlayer() {
        val session = VideoSession()
        val first = linkedMapOf<String,String>()
        val changed = VideoSettings(aspect = VideoAspect.Cinema, brightness = 15, zoom = 0.3)
        assertTrue(session.update(changed, { k,v -> first[k]=v; 0 }, { first[it] }))
        val replacement = linkedMapOf<String,String>()
        assertTrue(session.apply({ k,v -> replacement[k]=v; 0 }, { replacement[it] }))
        assertEquals(changed.properties(), replacement)
    }
    @Test fun defaultsRestoreEveryPropertyAndBoundAdjustments() {
        val defaults = VideoSettings().properties()
        assertEquals(mapOf("keepaspect" to "yes", "panscan" to "0", "video-aspect-override" to "no", "video-zoom" to "0.0", "brightness" to "0", "contrast" to "0", "saturation" to "0", "gamma" to "0"), defaults)
        val changed = VideoSettings(zoom = 9.0, brightness = -999, contrast = 999, saturation = 20, gamma = 10, fit = VideoFit.Fill, aspect = VideoAspect.FourThree).bounded()
        assertEquals(1.0, changed.zoom)
        assertEquals(-100, changed.brightness)
        assertEquals(100, changed.contrast)
        assertEquals("1", changed.properties()["panscan"])
        assertEquals("4:3", changed.properties()["video-aspect-override"])
        val writes = linkedMapOf<String, String>()
        val session = VideoSession()
        session.update(changed, { k, v -> writes[k] = v; 0 }, { writes[it] })
        assertEquals(changed, session.settings)
        session.update(VideoSettings(), { k, v -> writes[k] = v; 0 }, { writes[it] })
        assertEquals(defaults, writes)
    }
}
