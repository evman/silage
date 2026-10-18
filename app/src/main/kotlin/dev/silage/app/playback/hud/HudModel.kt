package dev.silage.app.playback.hud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** `m:ss`, or `h:mm:ss` from an hour up. */
fun formatTime(seconds: Double): String {
    val total = if (seconds.isNaN() || seconds < 0) 0L else seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Shows the HUD on activity and hides it [hideAfterMs] later, unless paused or in a menu. */
class HudVisibility(private val hideAfterMs: Long, shownAt: Long = System.currentTimeMillis()) {
    @Volatile private var lastActivity = shownAt

    fun activity(now: Long = System.currentTimeMillis()) { lastActivity = now }

    fun visible(now: Long, paused: Boolean, menuOpen: Boolean): Boolean =
        paused || menuOpen || now - lastActivity < hideAfterMs
}

data class Track(val id: Int, val label: String, val selected: Boolean)

/** A chapter from the file (mpv's `chapter-list`), by start time. */
data class Chapter(val title: String, val start: Double) {
    companion object {
        /** Parses mpv's `chapter-list` property (JSON); untitled chapters are numbered. */
        fun parse(json: String?): List<Chapter> = runCatching {
            Json.parseToJsonElement(json ?: "[]").jsonArray.mapIndexed { i, e ->
                val o = e.jsonObject
                Chapter(
                    title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}",
                    start = o["time"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            }.sortedBy { it.start }
        }.getOrDefault(emptyList())

        /** The chapter playing at [position], or -1 before the first. */
        fun indexAt(chapters: List<Chapter>, position: Double): Int = chapters.indexOfLast { it.start <= position + 0.5 }
    }
}

data class Tracks(val audio: List<Track>, val subtitles: List<Track>) {
    companion object {
        val Empty = Tracks(emptyList(), emptyList())

        /** Parses mpv's `track-list` property (JSON). */
        fun parse(json: String): Tracks = runCatching {
            val all = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
            fun of(type: String) = all.filter { it.str("type") == type }.map { t ->
                Track(t.int("id") ?: 0, label(t, type), t.bool("selected"))
            }
            Tracks(of("audio"), of("sub"))
        }.getOrDefault(Empty)

        private fun label(t: JsonObject, type: String): String {
            val language = t.str("lang")?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
            val codec = t.str("codec")?.let(::codecName)
            val parts = buildList {
                val title = t.str("title")
                title?.let(::add)
                add(
                    when {
                        language != null && title?.contains(language, ignoreCase = true) == true -> null
                        language != null -> language
                        title == null -> "Track ${t.int("id")}"
                        else -> null
                    },
                )
                if (type == "audio") add(listOfNotNull(codec, channels(t.int("demux-channel-count"))).joinToString(" ").ifBlank { null })
                else if (codec == "PGS" || codec == "VobSub") add(codec)
                if (t.bool("forced")) add("Forced")
                if (t.bool("external")) add("External")
            }
            return parts.filterNotNull().joinToString(" · ")
        }

        private fun codecName(c: String) = when (c) {
            "truehd" -> "TrueHD"; "eac3" -> "E-AC3"; "ac3" -> "AC3"; "dts" -> "DTS"; "aac" -> "AAC"
            "flac" -> "FLAC"; "opus" -> "Opus"; "mp3" -> "MP3"; "pcm_s16le", "pcm_s24le" -> "PCM"
            "hdmv_pgs_subtitle" -> "PGS"; "dvd_subtitle" -> "VobSub"
            else -> c.uppercase()
        }

        private fun channels(n: Int?) = when (n) {
            null -> null; 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch"
        }

        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
        private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull ?: false
    }
}

object HudCommands {
    fun audio(id: Int) = arrayOf("set", "aid", id.toString())
    fun subtitle(id: Int?) = arrayOf("set", "sid", id?.toString() ?: "no")
    fun speed(value: Double) = arrayOf("set", "speed", value.toString())
    fun seekTo(seconds: Double) = arrayOf("seek", "%.3f".format(Locale.ROOT, seconds), "absolute+exact")
}

enum class HudKey {
    Back, Escape, PlayPause, Left, Right, Up, Down, Enter, Info, Fullscreen;

    companion object {
        fun ofAwt(code: Int): HudKey? = when (code) {
            java.awt.event.KeyEvent.VK_ESCAPE -> Escape
            java.awt.event.KeyEvent.VK_BACK_SPACE, java.awt.event.KeyEvent.VK_Q -> Back
            java.awt.event.KeyEvent.VK_SPACE, java.awt.event.KeyEvent.VK_K -> PlayPause
            java.awt.event.KeyEvent.VK_LEFT -> Left
            java.awt.event.KeyEvent.VK_RIGHT -> Right
            java.awt.event.KeyEvent.VK_UP -> Up
            java.awt.event.KeyEvent.VK_DOWN -> Down
            java.awt.event.KeyEvent.VK_ENTER -> Enter
            java.awt.event.KeyEvent.VK_I -> Info
            java.awt.event.KeyEvent.VK_F -> Fullscreen
            else -> null
        }

        /** mpv key names, as bound in [MPV_KEYS]. */
        fun ofMpv(name: String): HudKey? = when (name) {
            "ESC" -> Escape
            "BS", "q" -> Back
            "SPACE", "k" -> PlayPause
            "LEFT" -> Left
            "RIGHT" -> Right
            "UP" -> Up
            "DOWN" -> Down
            "ENTER" -> Enter
            "i" -> Info
            "f", "F" -> Fullscreen
            else -> null
        }

        val MPV_KEYS = listOf("ESC", "BS", "q", "SPACE", "k", "LEFT", "RIGHT", "UP", "DOWN", "ENTER", "i", "f", "F")
    }
}

/** The HUD controls reachable with the arrow keys. */
enum class HudFocus { Skip, Seek, Back10, PlayPause, Fwd10, Audio, Subtitles, Speed, Chapters, Video, Fullscreen, Info }

sealed interface HudEffect {
    data object None : HudEffect
    data object Exit : HudEffect
    data object TogglePause : HudEffect
    data object ToggleFullscreen : HudEffect
    data object LeaveFullscreen : HudEffect
    data class AdjustVideo(val index: Int, val step: Int) : HudEffect
    data class SeekBy(val seconds: Double) : HudEffect
    data class Activate(val control: HudFocus) : HudEffect
    data class SelectRow(val menu: HudMenu, val index: Int) : HudEffect
}

/**
 * Remote-style navigation: hidden, ←/→ skip and anything else reveals the
 * HUD; shown, arrows move a focus ring and Enter presses; Back peels menu,
 * then HUD, then leaves the player.
 */
data class HudNav(
    val visible: Boolean = true,
    val focus: HudFocus = HudFocus.PlayPause,
    val menu: HudMenu = HudMenu.None,
    val menuIndex: Int = 0,
    val menuSize: Int = 0,
    val hasSkip: Boolean = false,
    val fullscreen: Boolean = false,
    /** The Chapters button only exists (and takes focus) when the file has chapters. */
    val hasChapters: Boolean = false,
) {
    private val row get() = if (hasChapters) ROW else ROW - HudFocus.Chapters

    fun on(key: HudKey): Pair<HudNav, HudEffect> {
        if (key == HudKey.Fullscreen) return copy(visible = true) to HudEffect.ToggleFullscreen
        if (key == HudKey.Escape) return if (fullscreen) this to HudEffect.LeaveFullscreen else on(HudKey.Back)
        if (menu == HudMenu.Video && key in listOf(HudKey.Left, HudKey.Right, HudKey.Enter))
            return this to HudEffect.AdjustVideo(menuIndex, if (key == HudKey.Left) -1 else 1)
        if (key == HudKey.PlayPause) return copy(visible = true) to HudEffect.TogglePause
        if (key == HudKey.Info) return copy(visible = true, menu = if (menu == HudMenu.Info) HudMenu.None else HudMenu.Info) to HudEffect.None
        if (!visible) return when (key) {
            HudKey.Back -> this to HudEffect.Exit
            HudKey.Left -> copy(visible = true, focus = HudFocus.Seek) to HudEffect.SeekBy(-10.0)
            HudKey.Right -> copy(visible = true, focus = HudFocus.Seek) to HudEffect.SeekBy(10.0)
            else -> copy(visible = true, focus = if (hasSkip) HudFocus.Skip else HudFocus.PlayPause) to HudEffect.None
        }
        if (menu != HudMenu.None && menu != HudMenu.Info) return when (key) {
            HudKey.Back -> copy(menu = HudMenu.None) to HudEffect.None
            HudKey.Up -> copy(menuIndex = (menuIndex - 1).coerceAtLeast(0)) to HudEffect.None
            HudKey.Down -> copy(menuIndex = (menuIndex + 1).coerceAtMost((menuSize - 1).coerceAtLeast(0))) to HudEffect.None
            HudKey.Enter -> copy(menu = HudMenu.None) to HudEffect.SelectRow(menu, menuIndex)
            else -> this to HudEffect.None
        }
        return when (key) {
            HudKey.Back -> if (menu == HudMenu.Info) copy(menu = HudMenu.None) to HudEffect.None
                else copy(visible = false, focus = HudFocus.PlayPause) to HudEffect.None
            HudKey.Left, HudKey.Right -> {
                val step = if (key == HudKey.Left) -1 else 1
                when (focus) {
                    HudFocus.Seek -> this to HudEffect.SeekBy(10.0 * step)
                    HudFocus.Skip -> this to HudEffect.None
                    else -> copy(focus = row[(row.indexOf(focus) + step).coerceIn(0, row.size - 1)]) to HudEffect.None
                }
            }
            HudKey.Up -> copy(focus = when (focus) {
                HudFocus.Skip -> HudFocus.Skip
                HudFocus.Seek -> if (hasSkip) HudFocus.Skip else HudFocus.Seek
                else -> HudFocus.Seek
            }) to HudEffect.None
            HudKey.Down -> copy(focus = when (focus) {
                HudFocus.Skip -> HudFocus.Seek
                HudFocus.Seek -> HudFocus.PlayPause
                else -> focus
            }) to HudEffect.None
            HudKey.Enter -> this to if (focus == HudFocus.Seek) HudEffect.TogglePause else HudEffect.Activate(focus)
            else -> this to HudEffect.None
        }
    }

    companion object {
        private val ROW = listOf(HudFocus.Back10, HudFocus.PlayPause, HudFocus.Fwd10, HudFocus.Audio, HudFocus.Subtitles, HudFocus.Speed, HudFocus.Chapters, HudFocus.Info, HudFocus.Video, HudFocus.Fullscreen)
    }
}

/**
 * A "Skip Intro" / "Skip Credits" button, shown while inside a server marker.
 * During the credits of an episode with a next one it becomes "Next Episode".
 */
data class SkipPrompt(val label: String, val target: Double, val kind: Kind = Kind.Intro) {
    enum class Kind { Intro, Credits, NextEpisode }

    companion object {
        fun at(
            position: Double,
            intro: org.siloserver.silo.model.catalog.TimeRange?,
            credits: org.siloserver.silo.model.catalog.TimeRange?,
            hasNextEpisode: Boolean = false,
        ): SkipPrompt? {
            fun inside(r: org.siloserver.silo.model.catalog.TimeRange?) = r != null && position >= r.start && position < r.end - 1.0
            return when {
                inside(intro) -> SkipPrompt("Skip Intro", intro!!.end, Kind.Intro)
                inside(credits) && hasNextEpisode -> SkipPrompt("Next Episode", credits!!.end, Kind.NextEpisode)
                inside(credits) -> SkipPrompt("Skip Credits", credits!!.end, Kind.Credits)
                else -> null
            }
        }
    }
}

/** The Playback Info panel's rows, from mpv properties. */
object InfoRows {
    fun build(prop: (String) -> String?): List<Pair<String, String>> = buildList {
        val w = prop("video-params/w"); val h = prop("video-params/h")
        prop("video-format")?.let { add("Video" to listOfNotNull(it.uppercase(), if (w != null && h != null) "$w×$h" else null).joinToString(" ")) }
        val gamma = prop("video-params/gamma")
        val hdr = gamma == "pq" || gamma == "hlg"
        add("Dynamic range" to when (gamma) {
            "pq" -> "HDR10 (PQ${prop("video-params/primaries")?.let { ", " + it.uppercase() } ?: ""})"
            "hlg" -> "HLG"
            else -> "SDR"
        })
        val target = prop("video-target-params/gamma")
        add("Display" to when {
            target == "pq" || target == "hlg" -> "HDR passthrough"
            hdr -> "Tone-mapped to SDR"
            else -> "SDR"
        })
        add("Decoder" to when (val d = prop("hwdec-current")) {
            null, "no", "" -> "Software"
            else -> "${d.replace("vaapi", "VA-API").replace("nvdec", "NVDEC").replace("vulkan", "Vulkan")} (hardware)"
        })
        prop("video-bitrate")?.toDoubleOrNull()?.let { add("Bitrate" to "%.1f Mbps".format(Locale.ROOT, it / 1e6)) }
        prop("audio-codec-name")?.let { a -> add("Audio" to listOfNotNull(a.uppercase(), prop("audio-params/channel-count")?.let { "${it}ch" }).joinToString(" ")) }
        val drops = listOf("frame-drop-count", "decoder-frame-drop-count").sumOf { prop(it)?.toIntOrNull() ?: 0 }
        add("Dropped frames" to drops.toString())
        prop("demuxer-cache-duration")?.toDoubleOrNull()?.let { ahead ->
            val speed = prop("cache-speed")?.toDoubleOrNull()?.let { " · %.1f MB/s".format(Locale.ROOT, it / 1048576) } ?: ""
            add("Buffer" to "${formatTime(ahead)} ahead$speed")
        }
    }
}
