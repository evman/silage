package dev.silage.app.connect

sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data object Connected : ConnectState
    data class Error(val message: String) : ConnectState
}

enum class ProbeOutcome { Reachable, NeedsServerUpdate, Unreachable }

/** Probes the address first; only a reachable v2 server is saved. */
class ConnectPresenter(
    private val probe: suspend (url: String) -> ProbeOutcome,
    private val save: suspend (url: String) -> Unit,
) {
    suspend fun submit(input: String): ConnectState {
        val url = ServerAddress.normalize(input)
            ?: return ConnectState.Error("Enter an address like 192.168.1.5:8096 or https://media.example.com")
        return when (probe(url)) {
            ProbeOutcome.Unreachable -> ConnectState.Error("Couldn't reach $url. Check the address and that the server is running.")
            ProbeOutcome.NeedsServerUpdate -> ConnectState.Error("This Silo server is too old for Silage. Update the server and try again.")
            ProbeOutcome.Reachable -> runCatching { save(url) }.fold(
                onSuccess = { ConnectState.Connected },
                onFailure = { ConnectState.Error("Couldn't save this server. Try again.") },
            )
        }
    }
}
