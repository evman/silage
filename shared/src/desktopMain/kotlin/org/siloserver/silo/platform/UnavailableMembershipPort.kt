package org.siloserver.silo.platform

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.repository.port.MembershipPort

/**
 * Placeholder until Silage has a durable membership outbox (Android uses
 * Room). Favorite/watchlist changes end in upstream's NEEDS_RECONCILIATION
 * state because there is no authority to record them under; reads report an
 * error so callers fall back to server data.
 */
object UnavailableMembershipPort : MembershipPort {
    private fun unavailable(): Nothing = error("Membership storage is unavailable")

    override suspend fun read(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): ApiResult<Boolean> =
        ApiResult.Error(503, "membership_unavailable", "Membership storage is unavailable")
    override suspend fun pending(authority: DurableLoginAuthority, limit: Int): List<MembershipPort.Command> = emptyList()
    override suspend fun hasLegacyQuarantine(): Boolean = false
    override suspend fun dispatch(limit: Int): List<MembershipPort.Completion> = emptyList()
    override suspend fun readyCount(): Int = 0
    override suspend fun captureAuthority(): DurableLoginAuthority? = null
    override suspend fun record(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind, present: Boolean): MembershipPort.Command = unavailable()
    override suspend fun send(command: MembershipPort.Command): MembershipPort.Completion = unavailable()
    override suspend fun reconcile(command: MembershipPort.Command): MembershipPort.Completion = unavailable()
    override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): MembershipPort.Projection? = null
}
