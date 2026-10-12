package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure transport-authority tests mirroring the silo-server contract: seek is
 * host-only; play/pause is allowed for the host always and for guests only
 * under the guest_play_pause policy; no transport outside the Playing phase.
 *
 * These five cases mirror the per-app TvRoomTransportGateTest, returning Boolean.
 */
class RoomTransportAuthorityTest {

    private fun fixture(
        policy: GuestControlPolicy,
        role: MemberRole,
        canControl: Boolean,
        phase: RoomPhase = RoomPhase.Playing,
    ): RoomSnapshot = RoomSnapshot(
        roomId = "room-1",
        phase = phase,
        guestControlPolicy = policy,
        selfRole = role,
        selfCanControlTransport = canControl,
    )

    @Test
    fun hostMayPlayPauseAndSeek() {
        val s = fixture(GuestControlPolicy.HostOnly, MemberRole.Host, canControl = true)
        assertTrue(roomTransportAuthorized(s, RoomTransportIntent.PlayPause))
        assertTrue(roomTransportAuthorized(s, RoomTransportIntent.Seek))
    }

    @Test
    fun guestHostOnlyBlocked() {
        val s = fixture(GuestControlPolicy.HostOnly, MemberRole.Guest, canControl = false)
        assertFalse(roomTransportAuthorized(s, RoomTransportIntent.PlayPause))
        assertFalse(roomTransportAuthorized(s, RoomTransportIntent.Seek))
    }

    @Test
    fun guestPlayPausePolicyAllowsPlayPauseButNotSeek() {
        val s = fixture(GuestControlPolicy.GuestPlayPause, MemberRole.Guest, canControl = true)
        assertTrue(roomTransportAuthorized(s, RoomTransportIntent.PlayPause))
        assertFalse(roomTransportAuthorized(s, RoomTransportIntent.Seek))
    }

    @Test
    fun noTransportOutsidePlaying() {
        val s = fixture(GuestControlPolicy.GuestPlayPause, MemberRole.Host, canControl = true, phase = RoomPhase.Lobby)
        assertFalse(roomTransportAuthorized(s, RoomTransportIntent.PlayPause))
        assertFalse(roomTransportAuthorized(s, RoomTransportIntent.Seek))
    }

    @Test
    fun nullBlocked() {
        assertFalse(roomTransportAuthorized(null, RoomTransportIntent.PlayPause))
        assertFalse(roomTransportAuthorized(null, RoomTransportIntent.Seek))
    }

    @Test
    fun guestHostOnlyExternalPauseIsRestoredWithoutRequest() {
        val snapshot = fixture(
            GuestControlPolicy.HostOnly,
            MemberRole.Guest,
            canControl = false,
        ).copy(isPaused = false)

        assertEquals(
            ExternalPlayPauseDecision(
                restorePlayWhenReady = true,
                requestIsPaused = null,
            ),
            externalPlayPauseDecision(snapshot, localPlayWhenReady = false),
        )
    }

    @Test
    fun authorizedExternalPauseIsRestoredAndRequestedThroughRoom() {
        val snapshot = fixture(
            GuestControlPolicy.GuestPlayPause,
            MemberRole.Guest,
            canControl = true,
        ).copy(isPaused = false)

        assertEquals(
            ExternalPlayPauseDecision(
                restorePlayWhenReady = true,
                requestIsPaused = true,
            ),
            externalPlayPauseDecision(snapshot, localPlayWhenReady = false),
        )
    }

    @Test
    fun roomAppliedStateDoesNotLoopBackIntoTransportRequest() {
        val snapshot = fixture(
            GuestControlPolicy.GuestPlayPause,
            MemberRole.Guest,
            canControl = true,
        ).copy(isPaused = true)

        assertNull(externalPlayPauseDecision(snapshot, localPlayWhenReady = false))
    }

    @Test
    fun externalStateOutsidePlayingIsNotReconciled() {
        val snapshot = fixture(
            GuestControlPolicy.HostOnly,
            MemberRole.Guest,
            canControl = false,
            phase = RoomPhase.Lobby,
        )

        assertNull(externalPlayPauseDecision(snapshot, localPlayWhenReady = false))
        assertNull(externalPlayPauseDecision(null, localPlayWhenReady = false))
    }
}
