package org.siloserver.silo.model.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackStartPositionTest {
    @Test
    fun `session resume position is not erased by zero detail progress`() {
        assertEquals(
            318.5,
            resolvePlaybackStartPosition(
                overridePosition = null,
                sessionPosition = 318.5,
                detailPosition = 0.0,
            ),
        )
    }

    @Test
    fun `explicit override can intentionally start at zero`() {
        assertEquals(
            0.0,
            resolvePlaybackStartPosition(
                overridePosition = 0.0,
                sessionPosition = 318.5,
                detailPosition = 200.0,
            ),
        )
    }

    @Test
    fun `detail progress is a fallback when session position is empty`() {
        assertEquals(
            204.0,
            resolvePlaybackStartPosition(
                overridePosition = null,
                sessionPosition = 0.0,
                detailPosition = 204.0,
            ),
        )
    }

    @Test
    fun `start request omits empty detail progress`() {
        assertNull(resolvePlaybackStartRequestPosition(overridePosition = null, detailPosition = 0.0))
        assertEquals(204.0, resolvePlaybackStartRequestPosition(overridePosition = null, detailPosition = 204.0))
        assertEquals(0.0, resolvePlaybackStartRequestPosition(overridePosition = 0.0, detailPosition = 204.0))
    }

    // --- applyResumeRewind (skip-back-on-resume) ---

    @Test
    fun `fresh start is never rewound`() {
        assertEquals(0.0, applyResumeRewind(resolvedStartPosition = 0.0, isExplicitOverride = false, rewindSeconds = 7.0))
    }

    @Test
    fun `resume above threshold is rewound`() {
        assertEquals(593.0, applyResumeRewind(resolvedStartPosition = 600.0, isExplicitOverride = false, rewindSeconds = 7.0))
    }

    @Test
    fun `resume below threshold is not rewound`() {
        // 20s in is below the 30s resume-for-rewind threshold.
        assertEquals(20.0, applyResumeRewind(resolvedStartPosition = 20.0, isExplicitOverride = false, rewindSeconds = 7.0))
    }

    @Test
    fun `rewind is clamped at zero`() {
        assertEquals(0.0, applyResumeRewind(resolvedStartPosition = 31.0, isExplicitOverride = false, rewindSeconds = 100.0))
    }

    @Test
    fun `explicit override is never rewound`() {
        assertEquals(600.0, applyResumeRewind(resolvedStartPosition = 600.0, isExplicitOverride = true, rewindSeconds = 7.0))
    }

    @Test
    fun `zero rewind disables feature`() {
        assertEquals(600.0, applyResumeRewind(resolvedStartPosition = 600.0, isExplicitOverride = false, rewindSeconds = 0.0))
    }

    @Test
    fun `exactly at threshold is rewound`() {
        // 30.0 is the boundary; the policy rewinds at >= threshold.
        assertEquals(23.0, applyResumeRewind(resolvedStartPosition = 30.0, isExplicitOverride = false, rewindSeconds = 7.0))
    }

    @Test
    fun `non-finite start becomes fresh start`() {
        assertEquals(0.0, applyResumeRewind(resolvedStartPosition = Double.NaN, isExplicitOverride = false, rewindSeconds = 7.0))
        assertEquals(0.0, applyResumeRewind(resolvedStartPosition = Double.POSITIVE_INFINITY, isExplicitOverride = false, rewindSeconds = 7.0))
        assertEquals(0.0, applyResumeRewind(resolvedStartPosition = -5.0, isExplicitOverride = false, rewindSeconds = 7.0))
    }

    @Test
    fun `non-finite rewind disables the nudge`() {
        assertEquals(600.0, applyResumeRewind(resolvedStartPosition = 600.0, isExplicitOverride = false, rewindSeconds = Double.NaN))
        assertEquals(600.0, applyResumeRewind(resolvedStartPosition = 600.0, isExplicitOverride = false, rewindSeconds = Double.POSITIVE_INFINITY))
    }
}
