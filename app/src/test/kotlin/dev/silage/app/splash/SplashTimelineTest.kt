package dev.silage.app.splash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplashTimelineTest {
    private val t = SplashTimeline(introMs = 1600, fadeMs = 450, minimumVisibleMs = 0)

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

    @Test fun theNameFadesInAfterTheLogoAndLeavesWithTheSplash() {
        assertEquals(0f, t.frame(0, null).textAlpha)
        assertEquals(0f, t.frame(700, null).textAlpha, "the logo arrives first")
        assertEquals(1f, t.frame(1600, null).textAlpha)
        assertTrue(t.frame(1600, null).textRise == 0f)
        assertTrue(t.frame(1000, null).textRise > 0f, "the name drifts up into place")
    }

    @Test fun theDefaultSplashStaysFullyVisibleForFourSecondsEvenWhenReadyImmediately() {
        val d = SplashTimeline()
        assertEquals(1f, d.frame(900, readyAtMs = 100).logoAlpha, "the intro still completes")
        for (elapsed in listOf(1250L, 3500L, 3999L, 4000L)) {
            val frame = d.frame(elapsed, readyAtMs = 100)
            assertFalse(frame.finished, "must stay visible at $elapsed ms")
            assertEquals(1f, frame.screenAlpha, "must not fade before four seconds")
        }
        assertTrue(d.frame(4175, readyAtMs = 100).screenAlpha in 0.3f..0.7f)
        assertFalse(d.frame(4349, readyAtMs = 100).finished)
        assertTrue(d.frame(4350, readyAtMs = 100).finished)
    }

    @Test fun theDefaultSplashWaitsLongerThanFourSecondsWhenLoadingIsSlow() {
        val d = SplashTimeline()
        assertEquals(1f, d.frame(9000, readyAtMs = null).screenAlpha)
        assertFalse(d.frame(9000, readyAtMs = null).finished)
        assertEquals(1f, d.frame(9000, readyAtMs = 9000).screenAlpha)
        assertFalse(d.frame(9349, readyAtMs = 9000).finished)
        assertTrue(d.frame(9350, readyAtMs = 9000).finished)
    }

    @Test fun theLogoGrowsAsTheSplashFadesIntoTheApp() {
        val atStart = t.frame(1600, readyAtMs = 0).scale
        val midFade = t.frame(1600 + 225, readyAtMs = 0).scale
        assertTrue(midFade > atStart, "scale $atStart -> $midFade")
    }
}
