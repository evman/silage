package dev.silage.app.signin

import org.siloserver.silo.network.ApiResult

sealed interface SignInState {
    data object Idle : SignInState
    data object Working : SignInState
    data object SignedIn : SignInState
    data class Error(val message: String) : SignInState
}

class SignInPresenter(private val login: suspend (username: String, password: String) -> ApiResult<*>) {
    suspend fun submit(username: String, password: String): SignInState {
        if (username.isBlank() || password.isEmpty()) return SignInState.Error("Enter your username and password.")
        return when (val r = login(username.trim(), password)) {
            is ApiResult.Success -> SignInState.SignedIn
            is ApiResult.Error -> if (r.code == 401 || r.code == 403) SignInState.Error("Wrong username or password.")
                                  else SignInState.Error("Sign-in failed: ${r.message}")
            is ApiResult.NetworkError -> SignInState.Error("Can't reach the server. Try again.")
        }
    }
}
