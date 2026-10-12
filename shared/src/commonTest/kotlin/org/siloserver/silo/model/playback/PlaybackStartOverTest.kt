package org.siloserver.silo.model.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStartOverTest {

    @Test
    fun onlyExactZeroOverrideIsStartOver() {
        assertTrue(isExplicitStartOver(0.0))
        assertFalse(isExplicitStartOver(null))
        assertFalse(isExplicitStartOver(12.5))
        assertFalse(isExplicitStartOver(Double.NaN))
    }

    @Test
    fun startOverOverrideSurvivesSessionResolution() {
        // The exact regression: server echoes position 0 for a start-over
        // session; the resolved player start must be 0, not the stale detail
        // resume point.
        assertEquals(
            0.0,
            resolvePlaybackStartPosition(
                overridePosition = 0.0,
                sessionPosition = 0.0,
                detailPosition = 2400.0,
            ),
        )
    }
}
