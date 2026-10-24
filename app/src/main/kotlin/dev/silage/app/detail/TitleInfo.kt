package dev.silage.app.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.ui.Silage
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import java.util.Locale

/** One block of the info dialog: a heading and its label/value rows. */
data class InfoSection(val title: String, val rows: List<Pair<String, String>>)

/**
 * Everything the title page leaves out, as the info dialog lists it: details, the full cast
 * and crew, and for [file] (the copy that would play) its file, video, audio and subtitle tracks.
 * Empty sections are left out.
 */
fun titleInfoSections(item: ItemDetail, file: FileVersion?): List<InfoSection> = buildList<InfoSection> {
    fun MutableList<Pair<String, String>>.row(label: String, value: String?) { value?.trim()?.takeIf { it.isNotEmpty() }?.let { add(label to it) } }

    add(InfoSection("Details", buildList<Pair<String, String>> {
        row("Original title", item.originalTitle?.takeIf { it != item.title })
        row("Released", item.releaseDate)
        if (item.firstAirDate != null || item.lastAirDate != null) row("Aired", listOfNotNull(item.firstAirDate, item.lastAirDate).joinToString(" – "))
        row("Runtime", item.runtime.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") })
        row("Rating", item.contentRating)
        row("Genres", item.genres.joinToString(", "))
        row("Studios", item.studios.joinToString(", "))
        row("Networks", item.networks.joinToString(", "))
        row("Countries", item.countries.joinToString(", "))
        row("Seasons", item.seasonCount?.takeIf { it > 0 }?.toString())
        row("Episodes", item.episodeCount?.takeIf { it > 0 }?.toString())
        row("IMDb", item.imdbId)
        row("TMDB", item.tmdbId)
        row("TVDB", item.tvdbId)
    }))
    add(InfoSection("Cast", item.cast.sortedBy { it.order }.map { it.name to (it.character?.takeIf { c -> c.isNotBlank() } ?: "") }))
    // Crew grouped by job, in the order the server lists them: "Director: A, B".
    add(InfoSection("Crew", item.crew.groupBy { it.job?.takeIf { j -> j.isNotBlank() } ?: "Crew" }
        .map { (job, people) -> job to people.map { it.name }.distinct().joinToString(", ") }))

    if (file != null) {
        add(InfoSection("File", buildList<Pair<String, String>> {
            row("Name", file.fileName ?: file.filePath?.substringAfterLast('/')?.substringAfterLast('\\'))
            row("Container", file.container?.uppercase())
            row("Size", formatBytes(file.fileSize))
            row("Duration", file.duration.takeIf { it > 0 }?.let { formatDuration(it) })
            // The version's overall bitrate is in kilobits per second.
            row("Bitrate", file.bitrate.takeIf { it > 0 }?.let { formatKbps(it) })
            row("Added", file.addedAt?.substringBefore('T'))
        }))
        file.videoTracks.orEmpty().forEachIndexed { i, v ->
            add(InfoSection(if (file.videoTracks.orEmpty().size > 1) "Video ${i + 1}" else "Video", buildList<Pair<String, String>> {
                row("Codec", listOfNotNull(v.codec?.uppercase(), v.profile).joinToString(" "))
                row("Resolution", if (v.width != null && v.height != null) "${v.width}×${v.height}" else v.resolution)
                row("Frame rate", v.frameRate?.takeIf { it > 0 }?.let { "%.3f fps".format(Locale.ROOT, it).replace(Regex("\\.?0+ fps$"), " fps") })
                row("Bit depth", v.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" })
                row("Dynamic range", dynamicRange(v))
                row("Colour", listOfNotNull(v.colorPrimaries, v.colorTransfer, v.colorSpace).joinToString(" · "))
                row("Bitrate", v.bitrate?.takeIf { it > 0 }?.let { formatKbps(trackKbps(it)) })
            }))
        }
        add(InfoSection("Audio", file.audioTracks.orEmpty().map { a ->
            languageName(a.language) to listOfNotNull(
                a.title?.takeIf { it.isNotBlank() },
                a.codec?.let(::codecName),
                a.channelLayout?.takeIf { it.isNotBlank() } ?: a.channels?.let(::channels),
                a.bitrate?.takeIf { it > 0 }?.let { formatKbps(trackKbps(it)) },
                "Default".takeIf { a.isDefault },
            ).distinct().joinToString(" · ")
        }))
        add(InfoSection("Subtitles", file.subtitleTracks.orEmpty().map { s ->
            languageName(s.language) to listOfNotNull(
                s.title?.takeIf { it.isNotBlank() },
                s.codec?.let(::codecName),
                "Forced".takeIf { s.forced },
                "Default".takeIf { s.isDefault },
                "External".takeIf { s.external },
            ).distinct().joinToString(" · ")
        }))
    }
}.filter { it.rows.isNotEmpty() }

private fun dynamicRange(v: org.siloserver.silo.model.catalog.VideoTrack): String? {
    val dv = v.dolbyVisionProfile?.takeIf { it > 0 }?.let { "Dolby Vision (Profile $it)" }
        ?: v.dolbyVision?.takeIf { it.isNotBlank() }?.let { "Dolby Vision" }
    val hdr = v.hdrFormat?.takeIf { it.isNotBlank() } ?: if (v.hdr) "HDR" else null
    return listOfNotNull(dv, hdr).distinct().joinToString(" · ").ifBlank { "SDR" }
}

/** "58.3 GB", "712 MB"; null for an unknown size. */
internal fun formatBytes(bytes: Long): String? {
    if (bytes <= 0) return null
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    return if (unit == 0 || value >= 100) "${value.toLong()} ${units[unit]}" else "%.1f %s".format(Locale.ROOT, value, units[unit])
}

/** "1h 32m 2s", "48m 10s". */
internal fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return listOfNotNull(h.takeIf { it > 0 }?.let { "${it}h" }, "${m}m", "${s}s").joinToString(" ")
}

/** A kbps rate as "95.9 Mbps" or "640 kbps". */
internal fun formatKbps(kbps: Int): String =
    org.siloserver.silo.model.playback.formatPlaybackQualityBitrate(kbps) ?: "$kbps kbps"

/** A track's bitrate as the scanner stored it: bits per second (a value under 100 000 is taken as kbps already). */
internal fun trackKbps(value: Int): Int = if (value >= 100_000) value / 1000 else value

private fun languageName(tag: String?): String =
    tag?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } } ?: "Unknown language"

private fun codecName(c: String) = when (c.lowercase()) {
    "truehd" -> "TrueHD"; "eac3" -> "E-AC3"; "ac3" -> "AC3"; "dts" -> "DTS"; "aac" -> "AAC"; "flac" -> "FLAC"; "opus" -> "Opus"
    "mp3" -> "MP3"; "subrip", "srt" -> "SRT"; "ass", "ssa" -> "ASS"; "hdmv_pgs_subtitle", "pgs", "pgssub" -> "PGS"
    "dvd_subtitle", "vobsub" -> "VobSub"; "webvtt", "vtt" -> "WebVTT"; "mov_text" -> "Timed text"
    else -> c.uppercase()
}

private fun channels(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch" }

/** The info dialog over the title page: Escape, Backspace or a click outside closes it. */
@Composable
internal fun TitleInfoDialog(title: String, sections: List<InfoSection>, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && (e.key == Key.Escape || e.key == Key.Backspace)) { onDismiss(); true } else false
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
        Column(
            Modifier.width(980.dp).fillMaxHeight(0.86f).clip(RoundedCornerShape(18.dp)).background(Color(0xF2171717))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .focusRequester(focus).focusable()
                .verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Close", color = Silage.Text2, fontSize = 18.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 10.dp))
            }
            sections.forEach { section ->
                Text(section.title.uppercase(), color = Silage.Text3, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 30.dp, bottom = 10.dp))
                section.rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(label, color = Silage.Text2, fontSize = 18.sp, modifier = Modifier.width(300.dp))
                        Text(value, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
