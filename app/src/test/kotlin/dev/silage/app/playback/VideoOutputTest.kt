package dev.silage.app.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoOutputTest {
    // Shape of ~/.config/kwinoutputconfig.json (KDE Plasma 6), trimmed.
    private val kwin = """
        [{"name":"outputs","data":[
          {"connectorName":"DP-1","highDynamicRange":false,"scale":1,"mode":{"width":2560,"height":1440}},
          {"connectorName":"DP-3","highDynamicRange":true,"scale":1,"mode":{"width":3840,"height":2160}},
          {"connectorName":"DP-2","highDynamicRange":false,"scale":1.5,"mode":{"width":2560,"height":1440}}
        ]},
        {"name":"setups","data":[
          {"outputs":[{"enabled":true,"outputIndex":0,"position":{"x":0,"y":0}},
                      {"enabled":true,"outputIndex":1,"position":{"x":2560,"y":0}}]},
          {"outputs":[{"enabled":true,"outputIndex":0,"position":{"x":3840,"y":2160}},
                      {"enabled":true,"outputIndex":1,"position":{"x":0,"y":1440}},
                      {"enabled":true,"outputIndex":2,"position":{"x":1076,"y":0}}]}
        ]}]
    """.trimIndent()

    @Test fun picksTheSetupMatchingTheConnectedScreensAndFindsTheOutputUnderAPoint() {
        val outputs = KwinOutputs.parse(kwin, screenOrigins = setOf(3840 to 2160, 0 to 1440, 1076 to 0))
        val under = KwinOutputs.outputAt(outputs, 1920, 2500)
        assertEquals("DP-3", under?.name)
        assertTrue(under!!.hdr)
        // Scale shrinks the logical size: DP-2 is 2560/1.5 wide.
        assertEquals(1707, outputs.first { it.name == "DP-2" }.width)
        assertNull(KwinOutputs.outputAt(outputs, 9000, 9000))
    }

    @Test fun malformedConfigYieldsNoOutputs() {
        assertEquals(emptyList(), KwinOutputs.parse("not json", setOf(0 to 0)))
    }

    @Test fun hdrMonitorGetsMpvsOwnNativeWindowOnThatMonitor() {
        val out = VideoOutput.choose(wid = 7L, under = Output("DP-3", 0, 0, 1, 1, hdr = true), override = null)
        assertEquals(VideoOutput.OwnWindow("DP-3"), out)
    }

    @Test fun sdrMonitorEmbedsInTheSilageWindow() {
        assertEquals(VideoOutput.Embedded(7L), VideoOutput.choose(7L, Output("DP-1", 0, 0, 1, 1, hdr = false), null))
        assertEquals(VideoOutput.Embedded(7L), VideoOutput.choose(7L, under = null, override = null))
    }

    @Test fun overrideForcesEitherWay() {
        assertEquals(VideoOutput.OwnWindow("DP-1"), VideoOutput.choose(7L, Output("DP-1", 0, 0, 1, 1, false), "on"))
        assertEquals(VideoOutput.Embedded(7L), VideoOutput.choose(7L, Output("DP-3", 0, 0, 1, 1, true), "off"))
        assertEquals(VideoOutput.OwnWindow(null), VideoOutput.choose(null, null, "off"))
    }

    @Test fun ownWindowIsNativeWaylandFullscreenOnTheChosenScreenWithHdrHint() {
        val o = VideoOutput.OwnWindow("DP-3").mpvOptions()
        assertEquals("yes", o["fullscreen"])
        assertEquals("DP-3", o["fs-screen-name"])
        // Native Wayland for HDR; X11 keeps playback working where Wayland is unavailable.
        assertEquals("waylandvk,x11vk", o["gpu-context"])
        assertEquals("yes", o["target-colorspace-hint"])
        assertEquals("silage", o["wayland-app-id"])
        assertFalse("wid" in o)
    }

    @Test fun embeddedDrawsIntoTheAppWindow() {
        val o = VideoOutput.Embedded(0x3a00007L).mpvOptions()
        assertEquals("60817415", o["wid"])
        assertEquals("x11vk", o["gpu-context"])
        assertEquals("no", o["input-vo-keyboard"])
        assertFalse("fullscreen" in o)
    }

    @Test fun everyOutputUsesHighQualityVideoAndADeepBuffer() {
        for (o in listOf(VideoOutput.Embedded(1).mpvOptions(), VideoOutput.OwnWindow(null).mpvOptions())) {
            assertEquals("gpu-next", o["vo"])
            // VA-API first: probing CUDA with the NVIDIA card absent stalls ~20 s.
            assertEquals("vaapi,auto-safe", o["hwdec"])
            assertEquals("yes", o["cache"])
            assertEquals("1024MiB", o["demuxer-max-bytes"])
            assertEquals("300", o["demuxer-readahead-secs"])
        }
    }
}
