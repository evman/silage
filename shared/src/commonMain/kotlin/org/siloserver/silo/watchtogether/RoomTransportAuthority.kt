package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot

/** A local transport intent originating from the UI: a play/pause toggle or a seek. */
enum class RoomTransportIntent { PlayPause, Seek }

/**
 * Reconciliation required when a platform media control changes the local
 * player directly instead of entering through the in-app room controls.
 *
 * [restorePlayWhenReady] immediately returns the player to the server-owned
 * room state. [requestIsPaused] is non-null only when the member is authorized
 * to ask the room to adopt the attempted state.
 */
data class ExternalPlayPauseDecision(
    val restorePlayWhenReady: Boolean,
    val requestIsPaused: Boolean?,
)

/**
 * Single source of truth for Watch Together transport authority, shared across
 * mobile and TV. Mirrors silo-server `internal/watchtogether/service.go`:
 *  - no transport outside the [RoomPhase.Playing] phase;
 *  - SEEK is host-only — the server rejects a guest's seek regardless of policy,
 *    so the affordance is disabled for ALL guests (even under guest_play_pause);
 *  - PLAY/PAUSE follows `self_can_control_transport` (true for the host always,
 *    and for guests only under the guest_play_pause policy).
 *
 * Returns true when the local member may broadcast the [intent] as a
 * `transport_request`.
 */
fun roomTransportAuthorized(snapshot: RoomSnapshot?, intent: RoomTransportIntent): Boolean {
    if (snapshot == null || snapshot.phase != RoomPhase.Playing) return false
    return when (intent) {
        RoomTransportIntent.Seek -> snapshot.selfRole == MemberRole.Host && snapshot.selfCanControlTransport
        RoomTransportIntent.PlayPause -> snapshot.selfCanControlTransport
    }
}

/**
 * Keeps notification, headset, Bluetooth, and other MediaSession controls from
 * creating a local-only fork from Watch Together playback.
 *
 * Every external toggle is first restored to the authoritative room state. An
 * authorized toggle is then requested through the room and will be applied to
 * every participant by the resulting scheduled transport command.
 */
fun externalPlayPauseDecision(
    snapshot: RoomSnapshot?,
    localPlayWhenReady: Boolean,
): ExternalPlayPauseDecision? {
    if (snapshot == null || snapshot.phase != RoomPhase.Playing) return null
    val authoritativePlayWhenReady = !snapshot.isPaused
    if (localPlayWhenReady == authoritativePlayWhenReady) return null
    return ExternalPlayPauseDecision(
        restorePlayWhenReady = authoritativePlayWhenReady,
        requestIsPaused = if (
            roomTransportAuthorized(snapshot, RoomTransportIntent.PlayPause)
        ) {
            !localPlayWhenReady
        } else {
            null
        },
    )
}
