package org.siloserver.silo.watchtogether

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomEntryUiPolicyTest {
    @Test
    fun `entry can dismiss only while no room mutation is running`() {
        assertTrue(canDismissRoomEntry(isBusy = false))
        assertFalse(canDismissRoomEntry(isBusy = true))
    }
}
