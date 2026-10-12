package dev.silage.app.playback.hud

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mpv copies every overlay on the thread that feeds audio. A full 4K HUD
 * (33 MB per redraw) caused ~1 PipeWire underrun a second (crackling audio);
 * a 1080p HUD scaled up by mpv caused none.
 */
class HudSceneSizeTest {
    @Test fun hudIsDrawnAtMost1080pAndScaledUpByMpv() {
        assertEquals(1920 to 1080, hudSceneSize(3840, 2160))
        assertEquals(1920 to 1080, hudSceneSize(2560, 1440))
    }

    @Test fun smallerOutputsAreDrawnAtTheirOwnSize() {
        assertEquals(1280 to 720, hudSceneSize(1280, 720))
    }

    @Test fun widescreenKeepsItsAspect() {
        assertEquals(2520 to 1080, hudSceneSize(5040, 2160))
    }
}
