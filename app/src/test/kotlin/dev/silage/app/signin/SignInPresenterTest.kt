package dev.silage.app.signin

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SignInPresenterTest {
    @Test fun success() = runTest {
        val p = SignInPresenter(login = { _, _ -> ApiResult.Success(Unit) })
        assertIs<SignInState.SignedIn>(p.submit("ann", "pw"))
    }

    @Test fun wrongPassword() = runTest {
        val p = SignInPresenter(login = { _, _ -> ApiResult.Error(401, "invalid_credentials", "bad") })
        assertEquals(SignInState.Error("Wrong username or password."), p.submit("ann", "nope"))
    }

    @Test fun networkError() = runTest {
        val p = SignInPresenter(login = { _, _ -> ApiResult.NetworkError(java.io.IOException("down")) })
        assertEquals(SignInState.Error("Can't reach the server. Try again."), p.submit("ann", "pw"))
    }

    @Test fun blankFieldsRejectedLocally() = runTest {
        val p = SignInPresenter(login = { _, _ -> error("must not be called") })
        assertEquals(SignInState.Error("Enter your username and password."), p.submit(" ", ""))
    }
}
