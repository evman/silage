package dev.silage.app.playback

/** Native mpv logs cannot be redacted before disk: never persist signed URLs. */
internal fun playbackMpvOptions(output: VideoOutput, paused: Boolean, muted: Boolean, exclusiveAudio: Boolean = false): Map<String, String> =
    output.mpvOptions() + ("pause" to if (paused) "yes" else "no") +
        (if (muted) mapOf("volume" to "0") else emptyMap()) +
        // Windows exclusive mode (WASAPI): Silage alone drives the device, bit-perfect, unmixed.
        (if (exclusiveAudio) mapOf("audio-exclusive" to "yes") else emptyMap())
