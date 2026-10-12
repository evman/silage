package dev.silage.app.detail

import org.siloserver.silo.model.catalog.EpisodeFile
import org.siloserver.silo.model.catalog.FileVersion
import java.util.Locale

/**
 * One copy of a title the user can choose to play. The server doesn't say
 * which library a copy is in, so copies are told apart by what they are
 * (4K HDR vs 1080p, codec, size, edition) — in practice the library choice.
 */
data class VersionOption(val fileId: Int, val short: String, val detail: String, val rank: Long)

private fun heightOf(resolution: String?, trackHeight: Int?): Int {
    trackHeight?.takeIf { it > 0 }?.let { return it }
    val r = resolution?.lowercase()?.trim() ?: return 0
    return when {
        r == "4k" || r == "uhd" -> 2160
        else -> r.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}

private fun resolutionLabel(height: Int): String? = when {
    height >= 2000 -> "4K"
    height >= 1000 -> "1080p"
    height >= 700 -> "720p"
    height > 0 -> "SD"
    else -> null
}

private fun videoCodecName(c: String?): String? = when (c?.lowercase()) {
    null, "" -> null
    "hevc", "h265" -> "HEVC"
    "h264", "avc", "avc1" -> "H.264"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "mpeg2video", "mpeg2" -> "MPEG-2"
    "vc1" -> "VC-1"
    else -> c.uppercase()
}

private fun audioCodecName(c: String?): String? = when (c?.lowercase()) {
    null, "" -> null
    "truehd" -> "TrueHD"
    "eac3" -> "E-AC3"
    "ac3" -> "AC3"
    "dts" -> "DTS"
    "aac" -> "AAC"
    "flac" -> "FLAC"
    "opus" -> "Opus"
    else -> c.uppercase()
}

private fun channelsName(n: Int?): String? = when (n) {
    null, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${n}ch"
}

private fun sizeName(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes >= 10_000_000_000 -> "${(bytes / 1_000_000_000.0).toInt()} GB"
    bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    else -> "${bytes / 1_000_000} MB"
}

/** HDR rank: Dolby Vision 3, named HDR format 2, plain HDR 1. */
private fun option(fileId: Int, height: Int, hdrName: String?, hdrRank: Int, video: String?, audio: String?, size: Long, bitrate: Int, edition: String?): VersionOption {
    val short = listOfNotNull(resolutionLabel(height), hdrName).joinToString(" · ").ifBlank { "Version $fileId" }
    val detail = listOfNotNull(edition?.takeIf { it.isNotBlank() }, short, videoCodecName(video), audio, sizeName(size)).joinToString(" · ")
    return VersionOption(fileId, short, detail, rank = height * 1_000_000L + hdrRank * 100_000L + bitrate.coerceIn(0, 99_999))
}

fun FileVersion.toOption(): VersionOption {
    val track = videoTracks?.firstOrNull()
    val (hdrName, hdrRank) = when {
        track?.dolbyVision != null -> "Dolby Vision" to 3
        !track?.hdrFormat.isNullOrBlank() -> track!!.hdrFormat!! to 2
        hdr || track?.hdr == true -> "HDR" to 1
        else -> null to 0
    }
    val audioTrack = audioTracks?.getOrNull(effectiveAudioTrackIndex ?: 0) ?: audioTracks?.firstOrNull()
    val audio = listOfNotNull(audioCodecName(audioTrack?.codec ?: codecAudio), channelsName(audioTrack?.channels)).joinToString(" ").ifBlank { null }
    return option(fileId, heightOf(resolution, track?.height), hdrName, hdrRank, codecVideo, audio, fileSize, bitrate, editionRaw)
}

fun EpisodeFile.toOption(): VersionOption =
    option(fileId, heightOf(resolution, null), if (hdr) "HDR" else null, if (hdr) 1 else 0, codecVideo, channelsName(audioChannels), fileSize, 0, null)

/** Highest quality first. */
fun List<VersionOption>.sortedBest(): List<VersionOption> = sortedByDescending { it.rank }

/** The copy played last time, else the best one. */
fun defaultVersion(options: List<VersionOption>, lastFileId: Int?): VersionOption? =
    options.firstOrNull { it.fileId == lastFileId } ?: options.maxByOrNull { it.rank }

/** The copy matching the kind the person picked (e.g. "4K · HDR"), else [defaultVersion]. */
fun chooseVersion(options: List<VersionOption>, preferredShort: String?, lastFileId: Int?): VersionOption? =
    preferredShort?.let { p -> options.firstOrNull { it.short == p } } ?: defaultVersion(options, lastFileId)
