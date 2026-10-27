package dev.silage.app.playback.hud

import dev.silage.app.playback.experimentalDvRenderPasses

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

/**
 * The pointer over the playing video: hidden while the HUD is, once the mouse
 * has been still for [hideAfterMs] too; any movement brings it back.
 */
class CursorAutoHide(private val hideAfterMs: Long = 3_000, now: Long = System.currentTimeMillis()) {
    @Volatile private var lastMove = now

    fun moved(now: Long = System.currentTimeMillis()) { lastMove = now }

    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    /** The pointer is at ([x], [y]) on screen; returns whether it really moved there. */
    fun movedTo(x: Int, y: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (x == lastX && y == lastY) return false
        lastX = x; lastY = y
        moved(now)
        return true
    }

    fun hidden(now: Long, hudVisible: Boolean): Boolean = !hudVisible && now - lastMove >= hideAfterMs
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
            fun of(type: String) = all.filter { it.str("type") == type }
                .let { if (type == "sub") withoutDuplicateSubtitles(it) else it }
                .map { t -> Track(t.int("id") ?: 0, label(t, type), t.bool("selected")) }
            Tracks(of("audio"), of("sub"))
        }.getOrDefault(Empty)

        /**
         * The server also offers each subtitle it can read as a file to load, so the same
         * English track showed up twice: once from the video and once "External". An
         * external track is dropped when the video already has one in the same language
         * with the same forced / SDH flags, unless it is the one playing.
         */
        internal fun withoutDuplicateSubtitles(subs: List<JsonObject>): List<JsonObject> {
            fun key(t: JsonObject): Triple<String, Boolean, Boolean>? {
                val lang = t.str("lang")?.let(::normalLanguage) ?: return null
                val title = t.str("title").orEmpty()
                val sdh = t.bool("hearing-impaired") || Regex("""\b(SDH|CC|HI)\b|hearing""", RegexOption.IGNORE_CASE).containsMatchIn(title)
                val forced = t.bool("forced") || title.contains("forced", ignoreCase = true)
                return Triple(lang, forced, sdh)
            }
            val internal = subs.filter { !it.bool("external") }.mapNotNull(::key).toSet()
            val seenExternal = mutableSetOf<Triple<String, Boolean, Boolean>>()
            return subs.filter { t ->
                if (!t.bool("external") || t.bool("selected")) return@filter true
                val k = key(t) ?: return@filter true
                k !in internal && seenExternal.add(k)
            }
        }

        /** "en", "eng" and "en-US" all as "en". */
        private fun normalLanguage(tag: String): String {
            val base = tag.trim().substringBefore('-').substringBefore('_').lowercase()
            if (base.length != 3) return base
            return runCatching { Locale.getAvailableLocales().firstOrNull { l -> runCatching { l.isO3Language == base }.getOrDefault(false) }?.language }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: base
        }

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
    Back, Escape, PlayPause, Left, Right, Up, Down, Enter, Info, Fullscreen,
    // Shortcuts: they act at once, with a short message, wherever the focus is.
    Mute, VolumeUp, VolumeDown, CycleSubtitles, CycleAudio, SlowerSpeed, FasterSpeed, NextEpisode;

    companion object {
        fun ofAwt(code: Int): HudKey? = when (code) {
            java.awt.event.KeyEvent.VK_ESCAPE -> Escape
            java.awt.event.KeyEvent.VK_BACK_SPACE, java.awt.event.KeyEvent.VK_Q -> Back
            // A remote's Back, Browser Back and the mouse's Back button (see BackInput).
            dev.silage.app.nav.BackInput.AWT_BACK, dev.silage.app.nav.BackInput.AWT_NAVIGATE_PREVIOUS -> Back
            java.awt.event.KeyEvent.VK_SPACE, java.awt.event.KeyEvent.VK_K -> PlayPause
            java.awt.event.KeyEvent.VK_LEFT -> Left
            java.awt.event.KeyEvent.VK_RIGHT -> Right
            java.awt.event.KeyEvent.VK_UP -> Up
            java.awt.event.KeyEvent.VK_DOWN -> Down
            java.awt.event.KeyEvent.VK_ENTER -> Enter
            java.awt.event.KeyEvent.VK_I -> Info
            java.awt.event.KeyEvent.VK_F -> Fullscreen
            java.awt.event.KeyEvent.VK_M -> Mute
            java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.KeyEvent.VK_PLUS, java.awt.event.KeyEvent.VK_ADD, java.awt.event.KeyEvent.VK_0 -> VolumeUp
            java.awt.event.KeyEvent.VK_MINUS, java.awt.event.KeyEvent.VK_SUBTRACT, java.awt.event.KeyEvent.VK_9 -> VolumeDown
            java.awt.event.KeyEvent.VK_S -> CycleSubtitles
            java.awt.event.KeyEvent.VK_A -> CycleAudio
            java.awt.event.KeyEvent.VK_OPEN_BRACKET -> SlowerSpeed
            java.awt.event.KeyEvent.VK_CLOSE_BRACKET -> FasterSpeed
            java.awt.event.KeyEvent.VK_N -> NextEpisode
            else -> null
        }

        /** mpv key names, as bound in [MPV_KEYS]. */
        fun ofMpv(name: String): HudKey? = when (name) {
            "ESC" -> Escape
            "BS", "q" -> Back
            // In mpv's own window: the mouse's Back button, and Browser Back / XF86Back.
            "MBTN_BACK", "GO_BACK" -> Back
            "SPACE", "k" -> PlayPause
            "LEFT" -> Left
            "RIGHT" -> Right
            "UP" -> Up
            "DOWN" -> Down
            "ENTER" -> Enter
            "i" -> Info
            "f", "F" -> Fullscreen
            "m", "M" -> Mute
            "+", "=", "KP_ADD", "0" -> VolumeUp
            "-", "KP_SUBTRACT", "9" -> VolumeDown
            "s", "S" -> CycleSubtitles
            "a", "A" -> CycleAudio
            "[" -> SlowerSpeed
            "]" -> FasterSpeed
            "n", "N" -> NextEpisode
            else -> null
        }

        val MPV_KEYS = listOf("ESC", "BS", "q", "MBTN_BACK", "GO_BACK", "SPACE", "k", "LEFT", "RIGHT", "UP", "DOWN", "ENTER", "i", "f", "F",
            "m", "M", "+", "=", "KP_ADD", "0", "-", "KP_SUBTRACT", "9", "s", "S", "a", "A", "[", "]", "n", "N")

        /** The shortcut keys and what each does, whatever the HUD shows. */
        internal fun shortcut(key: HudKey): HudEffect? = when (key) {
            Mute -> HudEffect.Mute
            VolumeUp -> HudEffect.Volume(+1)
            VolumeDown -> HudEffect.Volume(-1)
            CycleSubtitles -> HudEffect.CycleSubtitles
            CycleAudio -> HudEffect.CycleAudio
            SlowerSpeed -> HudEffect.Speed(-1)
            FasterSpeed -> HudEffect.Speed(+1)
            NextEpisode -> HudEffect.NextEpisode
            else -> null
        }
    }
}

/** The HUD controls reachable with the arrow keys. */
enum class HudFocus { Skip, Seek, Back10, PlayPause, Fwd10, Audio, Subtitles, Speed, Quality, Chapters, Video, Sync, Sleep, Fullscreen, Info }

sealed interface HudEffect {
    data object None : HudEffect
    data object Exit : HudEffect
    data object TogglePause : HudEffect
    data object ToggleFullscreen : HudEffect
    data class AdjustVideo(val index: Int, val step: Int) : HudEffect
    data class SeekBy(val seconds: Double) : HudEffect
    data class Activate(val control: HudFocus) : HudEffect
    data class SelectRow(val menu: HudMenu, val index: Int) : HudEffect
    data class AdjustSync(val index: Int, val step: Int) : HudEffect
    data class Volume(val step: Int) : HudEffect
    data object Mute : HudEffect
    data object CycleSubtitles : HudEffect
    data object CycleAudio : HudEffect
    data class Speed(val step: Int) : HudEffect
    data object NextEpisode : HudEffect
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
    /** Likewise the Quality button, when the server offers qualities. */
    val hasQuality: Boolean = false,
) {
    private val row get() = ROW.filter { (it != HudFocus.Chapters || hasChapters) && (it != HudFocus.Quality || hasQuality) }

    fun on(key: HudKey): Pair<HudNav, HudEffect> {
        if (key == HudKey.Fullscreen) return copy(visible = true) to HudEffect.ToggleFullscreen
        HudKey.shortcut(key)?.let { return this to it }
        if (key == HudKey.Escape) return if (menu != HudMenu.None) copy(menu = HudMenu.None) to HudEffect.None
            else this to HudEffect.Exit
        if (menu == HudMenu.Video && key in listOf(HudKey.Left, HudKey.Right, HudKey.Enter))
            return this to HudEffect.AdjustVideo(menuIndex, if (key == HudKey.Left) -1 else 1)
        if (menu == HudMenu.Sync && key in listOf(HudKey.Left, HudKey.Right, HudKey.Enter))
            return this to HudEffect.AdjustSync(menuIndex, if (key == HudKey.Left) -1 else 1)
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
        private val ROW = listOf(HudFocus.Back10, HudFocus.PlayPause, HudFocus.Fwd10, HudFocus.Audio, HudFocus.Subtitles, HudFocus.Speed, HudFocus.Quality, HudFocus.Chapters, HudFocus.Info, HudFocus.Video, HudFocus.Sync, HudFocus.Sleep, HudFocus.Fullscreen)
    }
}

/**
 * A "Skip Intro" / "Skip Credits" button, shown while inside a server marker.
 * During the credits of an episode with a next one it becomes "Next Episode".
 */
data class SkipPrompt(val label: String, val target: Double, val kind: Kind = Kind.Intro) {
    enum class Kind { Intro, Recap, Credits, NextEpisode }

    companion object {
        fun at(
            position: Double,
            intro: org.siloserver.silo.model.catalog.TimeRange?,
            credits: org.siloserver.silo.model.catalog.TimeRange?,
            hasNextEpisode: Boolean = false,
            recap: org.siloserver.silo.model.catalog.TimeRange? = null,
        ): SkipPrompt? {
            fun inside(r: org.siloserver.silo.model.catalog.TimeRange?) = r != null && position >= r.start && position < r.end - 1.0
            return when {
                inside(recap) -> SkipPrompt("Skip Recap", recap!!.end, Kind.Recap)
                inside(intro) -> SkipPrompt("Skip Intro", intro!!.end, Kind.Intro)
                inside(credits) && hasNextEpisode -> SkipPrompt("Next Episode", credits!!.end, Kind.NextEpisode)
                inside(credits) -> SkipPrompt("Skip Credits", credits!!.end, Kind.Credits)
                else -> null
            }
        }
    }
}

/** Which markers the player jumps past by itself (Settings → Playback). */
data class AutoSkip(val intro: Boolean = false, val recap: Boolean = false, val credits: Boolean = false)

/**
 * What to do by itself at [prompt] under [auto], given the kinds already handled this
 * playback (each marker is skipped once, so seeking back into it lets you watch it).
 */
fun autoSkipAction(prompt: SkipPrompt?, auto: AutoSkip, done: Set<SkipPrompt.Kind>): SkipPrompt.Kind? {
    val kind = prompt?.kind ?: return null
    val wanted = when (kind) {
        SkipPrompt.Kind.Intro -> auto.intro
        SkipPrompt.Kind.Recap -> auto.recap
        SkipPrompt.Kind.Credits, SkipPrompt.Kind.NextEpisode -> auto.credits
    }
    val family = if (kind == SkipPrompt.Kind.NextEpisode) SkipPrompt.Kind.Credits else kind
    return if (wanted && family !in done) kind else null
}

/** The Playback Info panel's rows, from mpv properties. */
object InfoRows {
    fun build(prop: (String) -> String?): List<Pair<String, String>> = buildList {
        val w = prop("video-params/w"); val h = prop("video-params/h")
        prop("video-format")?.let { add("Video" to listOfNotNull(it.uppercase(), if (w != null && h != null) "$w×$h" else null).joinToString(" ")) }
        val gamma = prop("video-params/gamma")
        val hdr = gamma == "pq" || gamma == "hlg"
        add("Dynamic range" to when (gamma) {
            // PQ is also used by Dolby Vision: transfer characteristics alone cannot identify HDR10.
            "pq" -> "HDR (PQ${prop("video-params/primaries")?.let { ", " + it.uppercase() } ?: ""})"
            "hlg" -> "HLG"
            else -> "SDR"
        })
        prop("current-tracks/video/dolby-vision-profile")?.toIntOrNull()?.takeIf { it > 0 }?.let { profile ->
            add("Source format" to "Dolby Vision (Profile $profile)")
            if (profile == 7) {
                val residual = prop("video-out-params/dolby-vision-residual")
                val active = experimentalDvRenderPasses(prop).any { "enhancement layer" in it }
                add("Enhancement layer" to when {
                    residual == "not-required" -> "MEL · no extra picture layer required"
                    residual == "required" && active -> "FEL · GPU processing active"
                    residual == "required" -> "FEL · not active"
                    active -> "GPU processing active"
                    else -> "Not active"
                })
            }
        }
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

/** Audio and subtitle timing in the player's Sync panel: each step is 0.1 seconds. */
object SyncControls {
    const val Step = 0.1
    const val AudioIndex = 0
    const val SubtitleIndex = 1
    const val ResetIndex = 2
    const val RowCount = 3
    val labels = listOf("Audio", "Subtitles")

    /** The delay after [step] steps, rounded to the tenth and kept within ±10 s. */
    fun adjust(current: Double, step: Int): Double = (Math.round((current + step * Step) * 10) / 10.0).coerceIn(-10.0, 10.0)

    /** "+0.3 s", "−1.2 s", "In sync". Positive is later. */
    fun label(seconds: Double): String = when {
        kotlin.math.abs(seconds) < 0.05 -> "In sync"
        seconds > 0 -> "+%.1f s later".format(Locale.ROOT, seconds)
        else -> "%.1f s earlier".format(Locale.ROOT, -seconds)
    }
}

/** The player's sleep timer choices; [minutes] null pauses at the end of the video (before any credits). */
enum class SleepChoice(val label: String, val minutes: Int?) {
    Off("Off", null), EndOfVideo("End of this video", null), Min30("In 30 minutes", 30), Min60("In 1 hour", 60), Min90("In 90 minutes", 90);
}

/** Where the "end of this video" timer pauses: the credits when the server marked them, else 3 s before the end. */
fun sleepEndPoint(duration: Double, creditsStart: Double?): Double? =
    creditsStart?.takeIf { it > 0 } ?: duration.takeIf { it > 3 }?.minus(3.0)

/** The next of [speeds] in [step]'s direction from [current], staying at the ends. */
fun nextSpeed(speeds: List<Double>, current: Double, step: Int): Double {
    val i = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.001 }.takeIf { it >= 0 } ?: speeds.indexOf(1.0)
    return speeds[(i + step).coerceIn(0, speeds.lastIndex)]
}

/** The track after the selected one in [ids] (null meaning off, when offered), wrapping round. */
fun <T> nextInCycle(ids: List<T>, selected: T): T = ids[(ids.indexOf(selected) + 1).mod(ids.size)]
