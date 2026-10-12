package dev.silage.app.playback

import dev.silage.app.BuildInfo
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext

/**
 * What mpv/FFmpeg can play on a modern Linux PC. Plan 3 replaces this static
 * list with probing (GPU decode, HDR, passthrough). Evidence tier is left at
 * the upstream default; passthrough is not claimed yet.
 */
object DesktopCapabilities {
    val codecs = ClientCodecCapabilities(
        codecsVideo = listOf("h264", "hevc", "av1", "vp9", "vp8", "mpeg2video", "mpeg4", "vc1"),
        codecsVideoHardware = listOf("h264", "hevc", "av1", "vp9"),
        codecsAudio = listOf("aac", "mp3", "opus", "vorbis", "flac", "alac", "ac3", "eac3", "dts", "truehd", "pcm"),
        containers = listOf("mkv", "mp4", "mov", "webm", "ts", "m2ts", "avi"),
        maxResolution = "2160p",
        hdr = true,
    )

    val context = ClientPlaybackContext(formFactor = "tv", appVersion = BuildInfo.VERSION)
}
