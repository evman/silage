package dev.silage.app.signin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RememberSignInUiTest {
    @Test fun signInRendersDiscoverableOptInAndCanDisableIt() = runComposeUiTest {
        var remember = false
        setContent { MaterialTheme {
            SignInScreen(SignInPresenter { _, _ -> ApiResult.Success(Unit) }, {}, {},
                remembered = false, onRememberChanged = { remember = it; true })
        } }
        onNodeWithText(rememberSignInLabel()).assertIsDisplayed()
        onNodeWithContentDescription("Remember me").assertIsOff().performClick().assertIsOn()
        assertTrue(remember)
        onNodeWithContentDescription("Remember me").performClick().assertIsOff()
        assertFalse(remember)
    }

    @Test fun failedOptInKeepsCheckboxOffAndShowsActionableError() = runComposeUiTest {
        setContent { MaterialTheme {
            SignInScreen(SignInPresenter { _, _ -> ApiResult.Success(Unit) }, {}, {},
                remembered = false, onRememberChanged = { false })
        } }
        onNodeWithContentDescription("Remember me").performClick().assertIsOff()
        onNodeWithText(signInStorageError()).assertIsDisplayed()
    }

    @Test fun serverErrorDoesNotEchoSensitiveResponse() = runTest {
        val presenter = SignInPresenter { _, _ -> ApiResult.Error(500, "failure", "dummy-secret-server-response") }
        val result = assertIs<SignInState.Error>(presenter.submit("fake", "fake"))
        assertFalse(result.message.contains("dummy-secret"))
    }

    @Test fun storageFailureIsActionableAndNeverLeaksExceptionSecrets() = runTest {
        val presenter = SignInPresenter { _, _ -> throw IllegalStateException("dummy-token-must-not-appear") }
        val result = assertIs<SignInState.Error>(presenter.submit("fake", "fake"))
        assertTrue(result.message.contains("Remember me"))
        assertFalse(result.message.contains("dummy-token"))
    }
}
