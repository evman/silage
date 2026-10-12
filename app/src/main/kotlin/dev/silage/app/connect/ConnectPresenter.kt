package dev.silage.app.connect

sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data object Connected : ConnectState
    data class Error(val message: String) : ConnectState
}

class ConnectPresenter(private val connect: suspend (url: String) -> Result<Unit>) {
    suspend fun submit(input: String): ConnectState {
        val url = ServerAddress.normalize(input)
            ?: return ConnectState.Error("Enter an address like 192.168.1.5:8096 or https://media.example.com")
        return connect(url).fold(
            onSuccess = { ConnectState.Connected },
            onFailure = { ConnectState.Error("Couldn't reach $url. Check the address and that the server is running.") },
        )
    }
}
