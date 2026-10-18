package dev.silage.app.playback

import kotlin.test.*

class VideoSettingsTest {
    @Test fun rejectsNativeReadbackMismatchInsteadOfShowingUnappliedValues() {
        val session = VideoSession()
        val defaults = VideoSettings().properties()
        assertFalse(session.update(VideoSettings(zoom = 0.5), { _, _ -> 0 }, { defaults[it] }))
        assertEquals(VideoSettings(), session.settings)
        assertTrue(session.error!!.contains("video-zoom"))
    }
    @Test fun sessionReappliesAdjustmentsToReplacementPlayer() {
        val session = VideoSession()
        val first = linkedMapOf<String,String>()
        val changed = VideoSettings(aspect = VideoAspect.Cinema, fit = VideoFit.Fill, zoom = 0.3)
        assertTrue(session.update(changed, { k,v -> first[k]=v; 0 }, { first[it] }))
        val replacement = linkedMapOf<String,String>()
        assertTrue(session.apply({ k,v -> replacement[k]=v; 0 }, { replacement[it] }))
        assertEquals(changed.properties(), replacement)
    }
    @Test fun defaultsRestoreEveryPropertyAndBoundAdjustments() {
        val defaults = VideoSettings().properties()
        // Only fit, aspect and zoom: picture adjustments (brightness etc.) are never touched.
        assertEquals(mapOf("keepaspect" to "yes", "panscan" to "0", "video-aspect-override" to "no", "video-zoom" to "0.0"), defaults)
        val changed = VideoSettings(zoom = 9.0, fit = VideoFit.Fill, aspect = VideoAspect.FourThree).bounded()
        assertEquals(1.0, changed.zoom)
        assertEquals(-1.0, VideoSettings(zoom = -9.0).bounded().zoom)
        assertEquals(VideoSettings(), VideoControls.adjust(changed, VideoControls.ResetIndex, 1))
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
