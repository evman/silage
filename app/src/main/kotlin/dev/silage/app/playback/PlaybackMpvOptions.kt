package dev.silage.app.playback

/** Native mpv logs cannot be redacted before disk: never persist signed URLs. */
internal fun playbackMpvOptions(output: VideoOutput, paused: Boolean, muted: Boolean): Map<String, String> =
    output.mpvOptions() + ("pause" to if (paused) "yes" else "no") +
        if (muted) mapOf("volume" to "0") else emptyMap()
