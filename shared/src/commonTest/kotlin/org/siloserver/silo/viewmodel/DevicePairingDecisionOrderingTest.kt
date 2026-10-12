package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A decision is more authoritative than a lookup, and it can finish first.
 *
 * canDecide stays true while an existing lookup refreshes — the previous
 * result is deliberately left on screen rather than blanked — so approving
 * mid-lookup is an ordinary thing to do, not a contrived one. The lookup then
 * lands last, and without a guard it overwrites the outcome of the decision
 * the viewer actually made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePairingDecisionOrderingTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun lookupResponse() = DeviceLoginLookupResponse(
        status = "pending",
        userCode = "ABCD-1234",
        matchCode = "42",
        deviceName = "Living Room TV",
        devicePlatform = "tvOS",
        ipAddressHint = "192.0.2.10",
    )

    private class FakeApi : DeviceLoginApi {
        val lookups = ArrayDeque<CompletableDeferred<ApiResult<DeviceLoginLookupResponse>>>()
        val decisions = ArrayDeque<CompletableDeferred<ApiResult<DeviceLoginDecisionResponse>>>()

        override suspend fun lookupDeviceLogin(token: String?, code: String?) =
            CompletableDeferred<ApiResult<DeviceLoginLookupResponse>>()
                .also { lookups.addLast(it) }
                .await()

        override suspend fun approveDeviceLogin(token: String?, code: String?) =
            CompletableDeferred<ApiResult<DeviceLoginDecisionResponse>>()
                .also { decisions.addLast(it) }
                .await()

        override suspend fun denyDeviceLogin(token: String?, code: String?) =
            CompletableDeferred<ApiResult<DeviceLoginDecisionResponse>>()
                .also { decisions.addLast(it) }
                .await()

        override suspend fun startDeviceLogin(
            deviceName: String?,
            devicePlatform: String?,
        ): ApiResult<DeviceLoginStartResponse> = error("unused")

        override suspend fun pollDeviceLogin(
            deviceCode: String,
        ): ApiResult<DeviceLoginPollResponse> = error("unused")
    }

    private fun viewModel(api: FakeApi) = DevicePairingViewModel(
        repository = DeviceLoginRepository(api),
        initialToken = "tok",
        initialCode = null,
    )

    @Test
    fun aLateLookupCannotOverwriteACompletedApproval() = runTest {
        val api = FakeApi()
        val vm = viewModel(api)

        // The initial lookup resolves, so the decision is informed.
        api.lookups.removeFirst().complete(ApiResult.Success(lookupResponse()))
        assertEquals(true, vm.uiState.value.canDecide)

        // Refreshing keeps the previous result on screen, so approving is still
        // offered — and taken — while that refresh is in flight.
        vm.lookup()
        val staleLookup = api.lookups.removeFirst()
        vm.approve()
        api.decisions.removeFirst().complete(
            ApiResult.Success(DeviceLoginDecisionResponse(status = "approved")),
        )
        assertEquals("approved", vm.uiState.value.completedStatus)

        // The retired lookup lands last. It must not paint an error over an
        // approval that already succeeded, nor blank the decision's outcome.
        staleLookup.complete(ApiResult.Error(code = 404, error = "gone", message = "Expired"))
        assertEquals("approved", vm.uiState.value.completedStatus)
        assertNull(vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.isLoading)
    }

    @Test
    fun aLateLookupCannotClearADecisionError() = runTest {
        val api = FakeApi()
        val vm = viewModel(api)
        api.lookups.removeFirst().complete(ApiResult.Success(lookupResponse()))

        vm.lookup()
        val staleLookup = api.lookups.removeFirst()
        vm.deny()
        api.decisions.removeFirst().complete(
            ApiResult.Error(code = 409, error = "conflict", message = "Already decided"),
        )
        val decisionError = vm.uiState.value.error

        // A successful stale lookup would otherwise clear the error the
        // decision reported, leaving the viewer believing the deny worked.
        staleLookup.complete(ApiResult.Success(lookupResponse()))
        assertEquals(decisionError, vm.uiState.value.error)
    }
}
