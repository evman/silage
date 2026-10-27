package dev.silage.app.signin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.siloserver.silo.model.auth.DeviceLoginCancelResponse
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.repository.DeviceSignInMachine
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SignInWithDeviceUiTest {
    private class Api(private val capable: Boolean = true) : DeviceLoginApi {
        val canceled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun deviceLoginCapabilityAt(serverUrl: String) =
            ApiResult.Success(DeviceLoginCapabilityResponse(cancel = true, deviceLoginAvailable = capable))
        override suspend fun startDeviceLoginAt(serverUrl: String, deviceName: String?, devicePlatform: String?) = ApiResult.Success(
            DeviceLoginStartResponse("dev-1", "4821-7730", "42", "https://silo.test/activate", "https://silo.test/activate?code=48217730",
                "2026-01-01T00:10:00Z", 600, 5, "htpc", "linux"),
        )
        override suspend fun pollDeviceLoginAt(serverUrl: String, deviceCode: String) = ApiResult.Success(DeviceLoginPollResponse("pending"))
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

    private fun presenter(api: Api, scope: CoroutineScope) = DeviceSignInPresenter(
        scope = scope,
        cancelScope = scope,
        newMachine = { url -> DeviceSignInMachine(DeviceLoginRepository(api), url, "htpc", "linux") },
        captureExpectation = { AccountSessionExpectation(1, "srv", "https://silo.test") },
        commit = { _, _ -> DeviceApprovalResult.SignedIn(false) },
        onSignedIn = {},
    )

    @Test fun passwordSignInWithdrawsTheCodeOnScreen() = runComposeUiTest {
        val api = Api()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var signedIn = 0
        setContent { MaterialTheme {
            SignInScreen(SignInPresenter { _, _ -> ApiResult.Success(Unit) }, { signedIn++ }, {}, device = presenter(api, scope), serverHost = "silo.test")
        } }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("4821 7730").fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasSetTextAction())[0].performTextInput("ann")
        onAllNodes(hasSetTextAction())[1].performTextInput("pw")
        onNodeWithText("Sign in").performClick()
        waitForIdle()
        // Real time, briefly: the code lives ten minutes, so only the password win withdraws it now.
        val deadline = System.currentTimeMillis() + 3_000
        while (api.canceled.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("dev-1"), api.canceled.toList())
        assertEquals(1, signedIn)
        scope.cancel()
    }

    @Test fun serverWithoutDeviceSignInShowsOnlyThePasswordForm() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        setContent { MaterialTheme {
            SignInScreen(SignInPresenter { _, _ -> ApiResult.Success(Unit) }, {}, {}, device = presenter(Api(capable = false), scope))
        } }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Or use your phone").fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Sign in").assertIsDisplayed()
        scope.cancel()
    }
}
