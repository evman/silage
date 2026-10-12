package org.siloserver.silo.model.download

import org.siloserver.silo.model.catalog.FileVersion

/**
 * Estimated on-device size for a pending download. Mirrors silo-apple's
 * `DownloadSizeEstimate` — Apple is authoritative.
 *
 * Sizes come from the catalog's original-file metadata, so for transcoded
 * qualities the estimate is an upper bound (no bitrate x duration math).
 * When no usable sizes exist the estimate is null and the UI shows nothing
 * rather than a fabricated number.
 */
data class DownloadSizeEstimate(
    val minBytes: Long,
    val maxBytes: Long,
    val isRange: Boolean,
) {
    val exceedsLargeThreshold: Boolean
        get() = maxBytes > LARGE_DOWNLOAD_THRESHOLD_BYTES

    /** [availableBytes] of 0 means the free-space lookup failed: never warn. */
    fun exceedsAvailableSpace(availableBytes: Long): Boolean =
        availableBytes > 0 && maxBytes > availableBytes

    companion object {
        /** 5 GB, decimal — matches Apple's large-download caveat threshold. */
        const val LARGE_DOWNLOAD_THRESHOLD_BYTES: Long = 5_000_000_000

        /** Exact estimate for a chosen [fileId]; min–max range across all
         *  [versions] when the server picks the file (fileId == null). */
        fun estimate(versions: List<FileVersion>, fileId: Int?): DownloadSizeEstimate? {
            if (fileId != null) {
                val size = versions.firstOrNull { it.fileId == fileId }?.fileSize
                    ?.takeIf { it > 0 } ?: return null
                return DownloadSizeEstimate(minBytes = size, maxBytes = size, isRange = false)
            }
            return estimate(fileSizes = versions.map { it.fileSize })
        }

        fun estimate(fileSizes: List<Long>): DownloadSizeEstimate? {
            val sizes = fileSizes.filter { it > 0 }
            val smallest = sizes.minOrNull() ?: return null
            val largest = sizes.maxOrNull() ?: return null
            return DownloadSizeEstimate(
                minBytes = smallest,
                maxBytes = largest,
                isRange = smallest != largest,
            )
        }
    }
}
