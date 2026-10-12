package dev.silage.app.playback

import dev.silage.app.BuildInfo
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.DELIVERY_CLASS_HLS
import org.siloserver.silo.model.playback.DELIVERY_CLASS_ORIGINAL_HTTP
import org.siloserver.silo.model.playback.DELIVERY_CLASS_PROGRESSIVE
import org.siloserver.silo.model.playback.DeliveryCapability
import org.siloserver.silo.model.playback.DeliverySubtitleCapabilities
import org.siloserver.silo.model.playback.NATIVE_EMBEDDED_SUBTITLE_CODECS
import org.siloserver.silo.model.playback.NATIVE_HLS_PLAYBACK_V1_FEATURE
import org.siloserver.silo.model.playback.NativeEmbeddedSubtitleCapability

/**
 * What mpv/FFmpeg can play on a modern Linux PC. Plan 3 replaces these static
 * lists with probing (GPU decode, HDR, passthrough). Passthrough and Android's
 * client-side recovery/transformation features are deliberately not claimed.
 */
object DesktopCapabilities {
    private val videoCodecs = listOf("h264", "hevc", "av1", "vp9", "vp8", "mpeg2video", "mpeg4", "vc1")
    private val audioCodecs = listOf("aac", "mp3", "opus", "vorbis", "flac", "alac", "ac3", "eac3", "dts", "truehd", "pcm")
    private val originalContainers = listOf("mkv", "matroska", "mp4", "m4v", "mov", "webm", "ts", "m2ts", "avi")

    val codecs = ClientCodecCapabilities(
        codecsVideo = videoCodecs,
        codecsVideoHardware = listOf("h264", "hevc", "av1", "vp9"),
        codecsAudio = audioCodecs,
        containers = originalContainers,
        maxResolution = "2160p",
        hdr = true,
        // mpv (FFmpeg + libplacebo) decodes every listed codec, falling back to
        // software, so flat lists are the truthful tier; "exact" without
        // per-codec decoder entries makes the server transcode everything.
        videoEvidence = "declared",
        audioEvidence = "declared",
        // HDR is passed through on an HDR monitor or tone-mapped by mpv, so the
        // server must not tone-map (which would mean a transcode).
        hdrDetails = HdrCapabilities(hdr10 = true, hdr10Plus = true, hlg = true, dolbyVisionProfiles = listOf(5, 8)),
    )

    /** mpv renders text, styled ASS (libass, with attached fonts) and bitmap subtitles itself. */
    private val mpvSubtitles = DeliverySubtitleCapabilities(
        nativeEmbedded = NATIVE_EMBEDDED_SUBTITLE_CODECS.map { (container, subtitleCodecs) ->
            NativeEmbeddedSubtitleCapability(
                container = container,
                codecs = subtitleCodecs,
                trackIdentity = "container_track_id",
                assStyling = "ass" in subtitleCodecs,
                fontAttachments = "ass" in subtitleCodecs,
            )
        },
        embeddedText = true,
        sidecarText = true,
        assStyling = true,
        embeddedBitmap = true,
        sidecarBitmap = true,
        fontAttachments = true,
    )

    /**
     * The server plans only through delivery classes listed here as enabled
     * and supported. Original file first; HLS as the transcode/remux fallback.
     */
    private val deliveries = mapOf(
        DELIVERY_CLASS_ORIGINAL_HTTP to DeliveryCapability(
            containers = originalContainers,
            videoCodecs = videoCodecs,
            audioDecodeCodecs = audioCodecs,
            subtitles = mpvSubtitles,
            features = listOf("track_switching", "audio_delay", "subtitle_delay", "buffer_reporting"),
        ),
        DELIVERY_CLASS_PROGRESSIVE to DeliveryCapability(
            enabled = false,
            supportedOnDevice = false,
            failureReason = "not_implemented",
        ),
        DELIVERY_CLASS_HLS to DeliveryCapability(
            containers = listOf("m3u8", "hls"),
            videoCodecs = videoCodecs,
            audioDecodeCodecs = audioCodecs,
            subtitles = mpvSubtitles.copy(nativeEmbedded = emptyList()),
            features = listOf("hls", "track_switching", "buffer_reporting", NATIVE_HLS_PLAYBACK_V1_FEATURE),
        ),
    )

    val context = ClientPlaybackContext(formFactor = "tv", appVersion = BuildInfo.VERSION, deliveries = deliveries)
}
