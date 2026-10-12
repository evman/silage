package dev.silage.app.splash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplashTimelineTest {
    private val t = SplashTimeline(introMs = 1600, fadeMs = 450)

    @Test fun startsInvisibleAndSoftThenBrightensByTheEndOfTheIntro() {
        val start = t.frame(0, readyAtMs = null)
        assertEquals(0f, start.logoAlpha)
        assertTrue(start.brightness < 0.9f)
        val end = t.frame(1600, readyAtMs = null)
        assertEquals(1f, end.logoAlpha)
        assertEquals(1f, end.brightness)
    }

    @Test fun neverFinishesWhileTheAppIsLoadingAndTheAmberGlowBreathes() {
        val frames = (2000L..9000L step 100).map { t.frame(it, readyAtMs = null) }
        assertTrue(frames.none { it.finished })
        assertTrue(frames.all { it.screenAlpha == 1f })
        assertTrue(frames.all { it.amber in 0f..1f })
        assertTrue(frames.maxOf { it.amber } - frames.minOf { it.amber } > 0.3f, "glow should pulse")
    }

    @Test fun aFastStartStillPlaysTheIntroThenFadesOut() {
        assertFalse(t.frame(1500, readyAtMs = 300).finished)
        assertEquals(1f, t.frame(1600, readyAtMs = 300).screenAlpha)
        val mid = t.frame(1600 + 225, readyAtMs = 300)
        assertTrue(mid.screenAlpha in 0.3f..0.7f, "alpha=${mid.screenAlpha}")
        assertTrue(t.frame(1600 + 450, readyAtMs = 300).finished)
    }

    @Test fun aSlowStartFadesOutAsSoonAsItIsReady() {
        assertEquals(1f, t.frame(5000, readyAtMs = 5000).screenAlpha)
        assertTrue(t.frame(5450, readyAtMs = 5000).finished)
    }
}
