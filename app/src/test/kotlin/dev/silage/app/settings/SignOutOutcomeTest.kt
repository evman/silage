package dev.silage.app.settings

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SignOutOutcomeTest {
    @Test fun storageFailureMustNotClaimSignOut() = runTest {
        assertFalse(signOutAccount(logout = { error("dummy-secret") }, hasSession = { true }))
    }
    @Test fun networkFailureStillSignsOutWhenLocalSecretsWereCleared() = runTest {
        assertTrue(signOutAccount(logout = { error("network unavailable") }, hasSession = { false }))
    }
}
