package dev.silage.app.signin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.DeviceLoginCancelResponse
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.repository.DeviceLoginClock
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.repository.DeviceSignInConfig
import org.siloserver.silo.repository.DeviceSignInMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSignInPresenterTest {
    private class ScriptedApi(
        var capability: ApiResult<DeviceLoginCapabilityResponse> =
            ApiResult.Success(DeviceLoginCapabilityResponse(cancel = true, openedSignal = true)),
        var start: (Int) -> ApiResult<DeviceLoginStartResponse> = { n -> ApiResult.Success(session("dev-$n")) },
        var poll: (String) -> ApiResult<DeviceLoginPollResponse> = { ApiResult.Success(DeviceLoginPollResponse("pending")) },
    ) : DeviceLoginApi {
        var starts = 0
        var polls = 0
        val canceled = mutableListOf<String>()
        override suspend fun deviceLoginCapabilityAt(serverUrl: String) = capability
        override suspend fun startDeviceLoginAt(serverUrl: String, deviceName: String?, devicePlatform: String?) = start(++starts)
        override suspend fun pollDeviceLoginAt(serverUrl: String, deviceCode: String): ApiResult<DeviceLoginPollResponse> {
            polls++
            return poll(deviceCode)
        }
        override suspend fun cancelDeviceLoginAt(serverUrl: String, deviceCode: String): ApiResult<DeviceLoginCancelResponse> {
            canceled += deviceCode
            return ApiResult.Success(DeviceLoginCancelResponse("canceled"))
        }
        override suspend fun startDeviceLogin(deviceName: String?, devicePlatform: String?) = error("unscoped")
        override suspend fun pollDeviceLogin(deviceCode: String) = error("unscoped")
        override suspend fun lookupDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginLookupResponse> = error("unused")
        override suspend fun approveDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> = error("unused")
        override suspend fun denyDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> = error("unused")
    }

    /** Background work isn't counted by advanceUntilIdle, so step virtual time instead. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private val expected = AccountSessionExpectation(generation = 7, serverId = "srv", serverUrl = "https://silo.test")

    private class Harness(val presenter: DeviceSignInPresenter) {
        val commits = mutableListOf<Pair<DeviceLoginPollResponse, AccountSessionExpectation>>()
        val signedIn = mutableListOf<Boolean>()
    }

    private fun TestScope.harness(
        api: ScriptedApi,
        config: DeviceSignInConfig = DeviceSignInConfig(),
        expectation: AccountSessionExpectation? = expected,
        result: DeviceApprovalResult = DeviceApprovalResult.SignedIn(profileSelected = false),
    ): Harness {
        lateinit var h: Harness
        val presenter = DeviceSignInPresenter(
            scope = backgroundScope,
            cancelScope = backgroundScope,
            newMachine = { url ->
                DeviceSignInMachine(DeviceLoginRepository(api), url, "htpc", "linux", DeviceLoginClock { testScheduler.currentTime }, config)
            },
            captureExpectation = { expectation },
            commit = { response, exp -> h.commits += response to exp; result },
            onSignedIn = { h.signedIn += it },
        )
        h = Harness(presenter)
        return h
    }

    @Test fun showsTheCodeGroupedWithItsQrAndActivateAddress() = runTest {
        val h = harness(ScriptedApi())
        assertEquals(DeviceSignInStatus.GettingCode, h.presenter.ui.value.status)
        h.presenter.start()
        runCurrent()
        val ui = h.presenter.ui.value
        assertEquals(DeviceSignInStatus.Waiting, ui.status)
        assertEquals(DeviceCode("4821 7730", "https://silo.test/activate?code=48217730", "silo.test/activate"), ui.code)
        assertTrue(!ui.hidden)
        h.presenter.stop()
    }

    @Test fun anOpenedCodeSaysContinueOnYourPhone() = runTest {
        val h = harness(ScriptedApi(poll = { ApiResult.Success(DeviceLoginPollResponse("pending", opened = true)) }))
        h.presenter.start()
        runCurrent()
        assertEquals(DeviceSignInStatus.Opened, h.presenter.ui.value.status)
        h.presenter.stop()
    }

    @Test fun aRenewedCodeIsAnnouncedBrieflyThenWaits() = runTest {
        val h = harness(ScriptedApi(poll = { code -> ApiResult.Success(DeviceLoginPollResponse(if (code == "dev-1") "expired" else "pending")) }))
        h.presenter.start()
        runCurrent()
        assertEquals(DeviceSignInStatus.NewCode, h.presenter.ui.value.status)
        advanceTimeBy(6_000)
        assertEquals(DeviceSignInStatus.Waiting, h.presenter.ui.value.status)
        h.presenter.stop()
    }

    @Test fun serverWithoutDeviceSignInHidesThePanel() = runTest {
        val h = harness(ScriptedApi(capability = ApiResult.Success(DeviceLoginCapabilityResponse(deviceLoginAvailable = false))))
        h.presenter.start()
        settle()
        assertTrue(h.presenter.ui.value.hidden)
    }

    @Test fun updateRequiredHidesThePanel() = runTest {
        val h = harness(ScriptedApi(start = { ApiResult.Error(426, "upgrade", "update") }))
        h.presenter.start()
        settle()
        assertTrue(h.presenter.ui.value.hidden)
    }

    @Test fun pausedOffersANewCodeAndShowNewCodeStartsOver() = runTest {
        // Codes that live a second and are never approved: renewed until the pause.
        val api = ScriptedApi(start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 1)) })
        val h = harness(api, DeviceSignInConfig(pauseAfterMs = 1_000))
        h.presenter.start()
        advanceTimeBy(5_000)
        assertEquals(DeviceSignInStatus.Paused, h.presenter.ui.value.status)
        assertTrue(h.presenter.ui.value.canShowNewCode)
        assertNull(h.presenter.ui.value.code)
        api.start = { n -> ApiResult.Success(session("dev-$n")) }
        val startsBefore = api.starts
        h.presenter.showNewCode()
        runCurrent()
        assertEquals(DeviceSignInStatus.Waiting, h.presenter.ui.value.status)
        assertEquals(startsBefore + 1, api.starts)
        h.presenter.stop()
    }

    @Test fun anUnreachableServerIsNamed() = runTest {
        val h = harness(ScriptedApi(start = { ApiResult.NetworkError(java.io.IOException("down")) }))
        h.presenter.start()
        advanceTimeBy(20_000)
        assertEquals(DeviceSignInStatus.Unreachable, h.presenter.ui.value.status)
        h.presenter.stop()
    }

    @Test fun approvalCommitsTheSessionUnderTheStartingContextAndSignsIn() = runTest {
        val approved = DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = "rt", expiresIn = 900)
        val h = harness(ScriptedApi(poll = { ApiResult.Success(approved) }))
        h.presenter.start()
        settle()
        assertEquals(listOf(approved to expected), h.commits)
        assertEquals(listOf(false), h.signedIn)
        assertEquals(DeviceSignInStatus.SignedIn, h.presenter.ui.value.status)
    }

    @Test fun approvalThatSelectedAProfileSaysSo() = runTest {
        val approved = DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = "rt", profileId = "p1")
        val h = harness(ScriptedApi(poll = { ApiResult.Success(approved) }), result = DeviceApprovalResult.SignedIn(profileSelected = true))
        h.presenter.start()
        settle()
        assertEquals(listOf(true), h.signedIn)
    }

    @Test fun aFailedCommitSaysSoAndOffersANewCode() = runTest {
        val approved = DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = "rt")
        val h = harness(ScriptedApi(poll = { ApiResult.Success(approved) }), result = DeviceApprovalResult.CouldntFinish)
        h.presenter.start()
        settle()
        assertEquals(DeviceSignInStatus.CouldntFinish, h.presenter.ui.value.status)
        assertTrue(h.presenter.ui.value.canShowNewCode)
        assertTrue(h.signedIn.isEmpty())
    }

    @Test fun deniedOffersANewCode() = runTest {
        val h = harness(ScriptedApi(poll = { ApiResult.Success(DeviceLoginPollResponse("denied")) }))
        h.presenter.start()
        settle()
        assertEquals(DeviceSignInStatus.Denied, h.presenter.ui.value.status)
        assertTrue(h.presenter.ui.value.canShowNewCode)
    }

    @Test fun stoppingEndsPollingAndWithdrawsTheCode() = runTest {
        val api = ScriptedApi()
        val h = harness(api)
        h.presenter.start()
        runCurrent()
        h.presenter.stop()
        runCurrent()
        val polls = api.polls
        advanceTimeBy(60_000)
        assertEquals(polls, api.polls)
        assertEquals(listOf("dev-1"), api.canceled)
        assertTrue(h.commits.isEmpty())
    }

    @Test fun withoutASignInContextNoCodeIsAskedFor() = runTest {
        val api = ScriptedApi()
        val h = harness(api, expectation = null)
        h.presenter.start()
        settle()
        assertEquals(DeviceSignInStatus.Failed, h.presenter.ui.value.status)
        assertEquals(0, api.starts)
    }

    private companion object {
        fun session(deviceCode: String, expiresIn: Int = 600) = DeviceLoginStartResponse(
            deviceCode = deviceCode,
            userCode = "4821-7730",
            matchCode = "42",
            verificationUri = "https://silo.test/activate",
            verificationUriComplete = "https://silo.test/activate?code=48217730",
            expiresAt = "2026-01-01T00:10:00Z",
            expiresIn = expiresIn,
            interval = 5,
            deviceName = "htpc",
            devicePlatform = "linux",
        )
    }
}
