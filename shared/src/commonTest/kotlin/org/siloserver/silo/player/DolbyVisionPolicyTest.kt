package org.siloserver.silo.player

import org.siloserver.silo.player.DolbyVisionPolicy.Resolution
import org.siloserver.silo.player.DolbyVisionPolicy.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors silo-apple's DolbyVisionPolicyTests — Apple is authoritative. */
class DolbyVisionPolicyTest {

    @Test
    fun profile5AlwaysPlaysDolbyVision() {
        val off = Snapshot(dolbyVisionEnabled = false)
        assertEquals(Resolution.DolbyVision, DolbyVisionPolicy.resolution(5, off))
        assertEquals(Resolution.DolbyVision, DolbyVisionPolicy.resolution(5, Snapshot()))
    }

    @Test
    fun dolbyVisionOffSupersedesProfile7Fallback() {
        val offWithFallback = Snapshot(dolbyVisionEnabled = false, preferProfile7HDR10Fallback = true)
        assertEquals(Resolution.DolbyVisionDisabled, DolbyVisionPolicy.resolution(7, offWithFallback))
    }

    @Test
    fun profile7RespectsFallbackToggleWhileDolbyVisionOn() {
        assertEquals(
            Resolution.Profile7HDR10Fallback,
            DolbyVisionPolicy.resolution(7, Snapshot(preferProfile7HDR10Fallback = true)),
        )
        assertEquals(Resolution.DolbyVision, DolbyVisionPolicy.resolution(7, Snapshot()))
    }

    @Test
    fun otherProfilesFollowTheToggle() {
        for (profile in listOf(8, 9, 10)) {
            assertEquals(Resolution.DolbyVision, DolbyVisionPolicy.resolution(profile, Snapshot()))
            assertEquals(
                Resolution.DolbyVisionDisabled,
                DolbyVisionPolicy.resolution(profile, Snapshot(dolbyVisionEnabled = false)),
            )
        }
    }

    @Test
    fun advertisableProfilesKeepsOnlyProfile5WhenOff() {
        val off = Snapshot(dolbyVisionEnabled = false)
        assertEquals(listOf(5), DolbyVisionPolicy.advertisableProfiles(listOf(5, 7, 8), off))
        assertEquals(listOf(5, 7, 8), DolbyVisionPolicy.advertisableProfiles(listOf(5, 7, 8), Snapshot()))
    }
}
