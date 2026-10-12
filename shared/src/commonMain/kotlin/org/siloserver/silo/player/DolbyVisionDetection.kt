package org.siloserver.silo.player

/**
 * Single source of truth for "does this source present as Dolby Vision?".
 * Mirrors how silo-apple recognizes Dolby Vision across its catalog and
 * playback surfaces so the phone, TV, and routing layers can never diverge:
 *
 *  - Structured profile field is primary — Apple derives a
 *    `dolbyVisionProfile` from the track's `dolby_vision` string and treats
 *    any parsed profile as authoritative
 *    (`ApplePlaybackRoutePlanner.dolbyVisionProfile(from:)`).
 *  - HDR-format tokens — Apple matches `dolby`/`dovi` and a bare `dv` token,
 *    case-insensitively, on the summary's HDR string
 *    (`TVFocusMarquee.badges`, `OverlayRegistry.hdrIcon` — `contains("DV")`).
 *  - Video-codec FourCC tokens — Apple flags `dvhe`/`dvh1`/`dva1`/`dvav`
 *    (and the spelled-out `dolbyvision`) as Dolby Vision
 *    (`PlaybackStats` / `codecLabel`).
 *
 * Kept free of platform state so it is unit-testable and callable from both
 * `androidApp` (player + detail UI) and `android-shared` (routing policy).
 * Apple is authoritative for the semantics.
 */
object DolbyVisionDetection {

    /**
     * True when any available signal marks the source as Dolby Vision. Each
     * caller passes whatever fields it has; absent fields are ignored. The
     * union matches Apple: structured profile (primary), HDR-format tokens,
     * and DV video-codec tokens.
     */
    fun isDolbyVision(
        dolbyVisionProfile: Int? = null,
        hdrFormat: String? = null,
        videoCodec: String? = null,
    ): Boolean =
        dolbyVisionProfile != null ||
            hdrFormatIndicatesDolbyVision(hdrFormat) ||
            videoCodecIsDolbyVision(videoCodec)

    /**
     * Recognizes Dolby Vision from an HDR-format label: `dolby`/`dovi`
     * anywhere, or a bare `dv` as a whole token (so "advanced" or "dvd" do
     * not false-positive). Case-insensitive, matching Apple.
     */
    fun hdrFormatIndicatesDolbyVision(hdrFormat: String?): Boolean {
        val value = hdrFormat?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (value.contains("dolby") || value.contains("dovi")) return true
        return value.split(nonTokenSeparator).any { it == "dv" }
    }

    /**
     * Recognizes Dolby Vision from a video-codec token. Uses the same
     * normalization and token set as `OriginalPlaybackRequestPolicy`'s codec
     * routing (`dolbyvision`/`dvhe`/`dvh1`/`dva1`/`dvav`) so the two never
     * drift.
     */
    fun videoCodecIsDolbyVision(videoCodec: String?): Boolean {
        val value = videoCodec.normalizedCodecToken() ?: return false
        return value.contains("dolbyvision") ||
            value.startsWith("dvhe") || value.startsWith("dvh1") ||
            value.startsWith("dva1") || value.startsWith("dvav")
    }

    private val nonTokenSeparator = Regex("[^a-z0-9]+")

    private fun String?.normalizedCodecToken(): String? =
        this
            ?.trim()
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.isNotBlank() }
}
