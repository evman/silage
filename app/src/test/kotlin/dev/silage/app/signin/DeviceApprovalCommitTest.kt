package dev.silage.app.signin

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceApprovalCommitTest {
    @Test fun approvalReplacesTheAccountSessionAndRefreshesTheContract() = runTest {
        val tokens = TokenManagerImpl()
        var committed = 0
        val expected = tokens.captureAccountSessionExpectation()!!
        val result = commitDeviceApproval(
            tokens, { committed++ },
            DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = "rt", expiresIn = 900),
            expected,
        )
        assertEquals(DeviceApprovalResult.SignedIn(profileSelected = false), result)
        assertEquals("at", tokens.getAccessToken())
        assertEquals("rt", tokens.getRefreshToken())
        assertNull(tokens.getProfileId())
        assertEquals(1, committed)
    }

    @Test fun aProfileTheApproverChoseIsSelected() = runTest {
        val tokens = TokenManagerImpl()
        val result = commitDeviceApproval(
            tokens, {},
            DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = "rt", profileId = "p1", profileToken = "pt"),
            tokens.captureAccountSessionExpectation()!!,
        )
        assertEquals(DeviceApprovalResult.SignedIn(profileSelected = true), result)
        assertEquals("p1", tokens.getProfileId())
        assertEquals("pt", tokens.getProfileToken())
    }

    @Test fun anAnswerWithoutTokensSavesNothing() = runTest {
        val tokens = TokenManagerImpl()
        var committed = 0
        val result = commitDeviceApproval(
            tokens, { committed++ },
            DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = null),
            tokens.captureAccountSessionExpectation()!!,
        )
        assertEquals(DeviceApprovalResult.CouldntFinish, result)
        assertNull(tokens.getAccessToken())
        assertEquals(0, committed)
    }

    @Test fun aSignInThatLandedFirstIsNotOverwritten() = runTest {
        val tokens = TokenManagerImpl()
        val expected = tokens.captureAccountSessionExpectation()!!
        // A password sign-in committed after the code was shown.
        tokens.replaceAccountSession(accessToken = "pw-at", refreshToken = "pw-rt", expiresIn = 900)
        var committed = 0
        val result = commitDeviceApproval(
            tokens, { committed++ },
            DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = "rt"),
            expected,
        )
        assertEquals(DeviceApprovalResult.CouldntFinish, result)
        assertEquals("pw-at", tokens.getAccessToken())
        assertEquals(0, committed)
    }
}
