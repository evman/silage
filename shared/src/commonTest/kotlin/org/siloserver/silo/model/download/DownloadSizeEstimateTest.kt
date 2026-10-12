package org.siloserver.silo.model.download

import org.siloserver.silo.model.catalog.FileVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors silo-apple's DownloadSizeEstimate — Apple is authoritative.
 *  Sizes come from catalog file metadata only (no bitrate math), so for
 *  transcoded qualities the estimate is an upper bound. */
class DownloadSizeEstimateTest {

    private fun version(fileId: Int, size: Long) = FileVersion(fileId = fileId, fileSize = size)

    @Test
    fun specificFileIdYieldsExactEstimate() {
        val estimate = DownloadSizeEstimate.estimate(
            versions = listOf(version(1, 2_000_000_000), version(2, 5_400_000_000)),
            fileId = 2,
        )
        assertEquals(5_400_000_000, estimate?.minBytes)
        assertEquals(5_400_000_000, estimate?.maxBytes)
        assertEquals(false, estimate?.isRange)
    }

    @Test
    fun noFileIdSpansAllVersionSizesAsRange() {
        val estimate = DownloadSizeEstimate.estimate(
            versions = listOf(version(1, 2_100_000_000), version(2, 5_400_000_000)),
            fileId = null,
        )
        assertEquals(2_100_000_000, estimate?.minBytes)
        assertEquals(5_400_000_000, estimate?.maxBytes)
        assertEquals(true, estimate?.isRange)
    }

    @Test
    fun equalSizesCollapseToExact() {
        val estimate = DownloadSizeEstimate.estimate(fileSizes = listOf(3_000L, 3_000L))
        assertEquals(false, estimate?.isRange)
    }

    @Test
    fun missingOrZeroSizesYieldNoEstimate() {
        assertNull(DownloadSizeEstimate.estimate(fileSizes = emptyList()))
        assertNull(DownloadSizeEstimate.estimate(fileSizes = listOf(0L, 0L)))
        assertNull(
            DownloadSizeEstimate.estimate(
                versions = listOf(version(1, 0)),
                fileId = 1,
            ),
        )
        assertNull(
            DownloadSizeEstimate.estimate(
                versions = listOf(version(1, 100)),
                fileId = 99,
            ),
        )
    }

    @Test
    fun zeroSizesAreIgnoredWithinAMixedList() {
        val estimate = DownloadSizeEstimate.estimate(fileSizes = listOf(0L, 900L, 400L))
        assertEquals(400, estimate?.minBytes)
        assertEquals(900, estimate?.maxBytes)
    }

    @Test
    fun largeThresholdIsFiveDecimalGigabytesOnMaxBytes() {
        val under = DownloadSizeEstimate(minBytes = 1, maxBytes = 5_000_000_000, isRange = true)
        val over = DownloadSizeEstimate(minBytes = 1, maxBytes = 5_000_000_001, isRange = true)
        assertFalse(under.exceedsLargeThreshold)
        assertTrue(over.exceedsLargeThreshold)
    }

    @Test
    fun availableSpaceCheckUsesMaxBytesAndTreatsZeroAvailableAsUnknown() {
        val estimate = DownloadSizeEstimate(minBytes = 100, maxBytes = 1_000, isRange = true)
        assertTrue(estimate.exceedsAvailableSpace(999))
        assertFalse(estimate.exceedsAvailableSpace(1_000))
        // 0 = free-space lookup failed: never warn.
        assertFalse(estimate.exceedsAvailableSpace(0))
    }
}
