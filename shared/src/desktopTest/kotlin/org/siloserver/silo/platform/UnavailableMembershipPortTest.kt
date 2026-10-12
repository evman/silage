package org.siloserver.silo.platform

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.repository.MembershipActions
import org.siloserver.silo.repository.port.MembershipPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnavailableMembershipPortTest {
    @Test fun hasNoAuthorityAndNothingQueued() = runTest {
        assertNull(UnavailableMembershipPort.captureAuthority())
        assertEquals(0, UnavailableMembershipPort.readyCount())
        assertTrue(UnavailableMembershipPort.dispatch().isEmpty())
        assertTrue(!UnavailableMembershipPort.hasLegacyQuarantine())
    }

    @Test fun membershipChangeEndsUnsavedInsteadOfCrashing() = runTest {
        val actions = MembershipActions(UnavailableMembershipPort, DefaultIdentityTransitionBarrier())
        val intent = actions.begin("movie-1", MembershipPort.Kind.WATCHLIST, present = true)
        actions.perform(intent)
        assertEquals(
            MembershipPort.Disposition.NEEDS_RECONCILIATION,
            actions.actions.value[intent.key]?.completion?.disposition,
        )
    }
}
