package dev.silage.player

data class PlayerState(
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val loaded: Boolean = false,
)

sealed interface PlayerEvent {
    data object FileLoaded : PlayerEvent
    data object PlaybackRestart : PlayerEvent
    data class Ended(val reason: EndReason, val error: String?) : PlayerEvent
    data class ClientMessage(val args: List<String>) : PlayerEvent
    data object Shutdown : PlayerEvent
}

/** Mirrors mpv_end_file_reason. */
enum class EndReason {
    EOF, STOP, QUIT, ERROR, REDIRECT, UNKNOWN;

    companion object {
        fun of(code: Int) = when (code) {
            0 -> EOF; 2 -> STOP; 3 -> QUIT; 4 -> ERROR; 5 -> REDIRECT; else -> UNKNOWN
        }
    }
}
