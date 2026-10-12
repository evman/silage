package org.siloserver.silo.player

/**
 * Pure decision logic for whether a Dolby Vision source presents as Dolby
 * Vision or falls back to its base layer, given the user's Dolby Vision
 * setting and the narrower Profile 7 HDR10 Fallback toggle. Single source of
 * truth so precedence between the two settings can never diverge between the
 * capability payload, track selection, and any future engine-specific
 * routing. Direct port of silo-apple's `DolbyVisionPolicy` (e9bd775) — Apple
 * is authoritative for the semantics.
 */
object DolbyVisionPolicy {

    /** Settings captured once at plan/load time. Dolby Vision off supersedes
     *  the Profile 7 fallback toggle. */
    data class Snapshot(
        val dolbyVisionEnabled: Boolean = true,
        val preferProfile7HDR10Fallback: Boolean = false,
    )

    /** How a given DV profile should present for this session. */
    enum class Resolution {
        /** Present as Dolby Vision, as far as the engine supports the profile. */
        DolbyVision,

        /** Present the Profile 7 base layer as HDR10 because the user opted
         *  into the Profile 7 fallback while Dolby Vision itself is on. */
        Profile7HDR10Fallback,

        /** Present the base layer without Dolby Vision because the user
         *  turned Dolby Vision off. */
        DolbyVisionDisabled,
    }

    /**
     * Profile 5 carries IPT-encoded pixels with no HDR10-compatible base
     * layer — playing it as HDR10 renders visibly wrong colors — so it always
     * resolves to Dolby Vision regardless of the setting.
     */
    fun resolution(profile: Int, snapshot: Snapshot): Resolution = when (profile) {
        5 -> Resolution.DolbyVision
        7 -> when {
            !snapshot.dolbyVisionEnabled -> Resolution.DolbyVisionDisabled
            snapshot.preferProfile7HDR10Fallback -> Resolution.Profile7HDR10Fallback
            else -> Resolution.DolbyVision
        }
        else ->
            if (snapshot.dolbyVisionEnabled) Resolution.DolbyVision
            else Resolution.DolbyVisionDisabled
    }

    /**
     * Filters the DV profiles a capability payload may advertise. With Dolby
     * Vision off, only profile 5 survives (it has no watchable base layer, so
     * hiding it would force a pointless server transcode to broken colors —
     * it plays as DV like on Apple).
     */
    fun advertisableProfiles(profiles: List<Int>, snapshot: Snapshot): List<Int> =
        profiles.filter { resolution(it, snapshot) != Resolution.DolbyVisionDisabled }
}
