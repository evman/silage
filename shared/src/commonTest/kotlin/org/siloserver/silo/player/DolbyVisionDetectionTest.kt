package org.siloserver.silo.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DV routing is high-risk (wrong route = black video), so the shared
 *  recognition predicate is covered directly. Mirrors silo-apple's token set. */
class DolbyVisionDetectionTest {

    @Test
    fun structuredProfileIsAuthoritative() {
        assertTrue(DolbyVisionDetection.isDolbyVision(dolbyVisionProfile = 5))
        assertTrue(DolbyVisionDetection.isDolbyVision(dolbyVisionProfile = 8))
        assertFalse(DolbyVisionDetection.isDolbyVision(dolbyVisionProfile = null))
    }

    @Test
    fun hdrFormatMatchesDolbyAndDoviAndBareDv() {
        for (label in listOf("Dolby Vision", "DOLBY", "dovi", "dv", "DV", "4K DV", "DV HDR")) {
            assertTrue(DolbyVisionDetection.isDolbyVision(hdrFormat = label), "expected DV for '$label'")
        }
    }

    @Test
    fun hdrFormatDvOnlyMatchesWholeToken() {
        // The pre-unification PlayerScreen predicate missed a bare "dv"; the
        // MediaSelectors badge only matched exact equality. Neither should
        // false-positive on unrelated substrings.
        assertFalse(DolbyVisionDetection.isDolbyVision(hdrFormat = "advanced"))
        assertFalse(DolbyVisionDetection.isDolbyVision(hdrFormat = "dvd"))
    }

    @Test
    fun plainHdrIsNotDolbyVision() {
        for (label in listOf("HDR10", "HDR10+", "HLG", "PQ", "SDR", null, "")) {
            assertFalse(DolbyVisionDetection.isDolbyVision(hdrFormat = label), "did not expect DV for '$label'")
        }
    }

    @Test
    fun videoCodecTokensMatchApple() {
        for (codec in listOf("dvhe", "dvh1", "dva1", "dvav", "DolbyVision", "dvhe.05.06", "dvh1.08.09")) {
            assertTrue(DolbyVisionDetection.isDolbyVision(videoCodec = codec), "expected DV for '$codec'")
        }
    }

    @Test
    fun nonDolbyVisionCodecsAreNotDetected() {
        for (codec in listOf("hevc", "hvc1", "h264", "avc1", "av1", "av01", "vp9", null, "")) {
            assertFalse(DolbyVisionDetection.isDolbyVision(videoCodec = codec), "did not expect DV for '$codec'")
        }
    }
}
