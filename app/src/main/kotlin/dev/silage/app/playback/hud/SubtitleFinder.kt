package dev.silage.app.playback.hud

/** A subtitle a provider offers for the playing file. */
data class SubtitleHit(val label: String, val detail: String, val key: Any)

/** One way to have the server create subtitles with AI (transcribe the audio, maybe translating). */
data class AiOption(val label: String, val key: Any)

/**
 * Server-side subtitle work the player offers from its Subtitles menu:
 * provider search and download, and AI creation. Implemented over Silo's
 * subtitle API; the HUD only drives it.
 */
interface SubtitleTools {
    /** What [search] looks for, for the panel title ("English"). */
    val searchLabel: String
    suspend fun search(): Result<List<SubtitleHit>>
    /** Stores [hit] on the server; returns its stored subtitle id. */
    suspend fun download(hit: SubtitleHit): Result<Int>
    /** The AI options this server and profile have; empty when AI subtitles are off. */
    suspend fun aiOptions(): List<AiOption>
    /** Starts AI creation and waits for it; returns the stored subtitle id. */
    suspend fun create(option: AiOption, positionSeconds: Double, onProgress: (fraction: Double, message: String) -> Unit): Result<Int>
    /** Switches playback to stored subtitle [id] (the stream restarts where it is). */
    fun use(id: Int)
}

/** The Find / AI panels' state. */
data class FinderUi(
    /** A line above the rows: progress, an error, "No subtitles found". */
    val status: String? = null,
    val busy: Boolean = false,
    val hits: List<SubtitleHit> = emptyList(),
    val aiOptions: List<AiOption> = emptyList(),
    /** A running AI job, for the Subtitles menu ("Creating subtitles · 42%"). */
    val aiProgress: String? = null,
)

/** The extra rows after the tracks in the Subtitles menu. */
enum class SubtitleExtra { Find, Ai }

fun subtitleExtras(hasTools: Boolean, finder: FinderUi): List<SubtitleExtra> =
    if (!hasTools) emptyList() else listOfNotNull(SubtitleExtra.Find, SubtitleExtra.Ai.takeIf { finder.aiOptions.isNotEmpty() || finder.aiProgress != null })

fun extraLabel(extra: SubtitleExtra, finder: FinderUi): String = when (extra) {
    SubtitleExtra.Find -> "Find subtitles…"
    SubtitleExtra.Ai -> finder.aiProgress ?: "Create subtitles with AI…"
}

/** "42%" style progress for an AI job, with the server's message when it has one. */
fun aiProgressLabel(fraction: Double, message: String): String {
    val pct = (fraction.coerceIn(0.0, 1.0) * 100).toInt()
    return if (message.isBlank()) "Creating subtitles · $pct%" else "Creating subtitles · $pct% · $message"
}

/** The keyboard's media keys, while a video plays. */
enum class MediaKey { PlayPause, Next, Previous, Stop }

/** Where a double-click on the picture toggles fullscreen (fractions of the height). */
internal val DoubleClickBand = 0.15f..0.72f
