package dev.silage.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finds a YouTube trailer's streams with yt-dlp before it is played: when its title page opens,
 * or when its card is pointed at. Finding them is most of a trailer's wait, so pressing play then
 * starts the video almost at once. Each lookup is kept for a while; YouTube's links last hours.
 */
object TrailerPrefetch {
    /** The streams yt-dlp chose, and the browser name YouTube expects them to be fetched with. */
    data class Streams(val video: String, val audio: String?, val userAgent: String?) {
        /** What mpv opens: the picture with the separate sound joined in, as mpv's own YouTube support does. */
        val playUrl: String get() =
            if (audio == null) video
            else "edl://" + listOf(video, audio).joinToString(";!new_stream;") { "!no_clip;!no_chapters;" + TrailerTools.literal(it) }
    }

    private class Entry(val at: Long, val job: Deferred<Streams?>)

    private const val KeepMs = 30 * 60_000L
    private const val TimeoutSeconds = 60L
    private val entries = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Pointing along a row of trailers shouldn't start a yt-dlp for each.
    private val running = Semaphore(2)

    /** Starts finding [url]'s streams if the tools are there; does nothing when already found or underway. */
    fun prefetch(url: String) { if (TrailerTools.ready()) streams(url) }

    /** [url]'s streams: the lookup already made or underway, else a new one. Null when yt-dlp couldn't find them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun streams(url: String): Deferred<Streams?> = entries.compute(url) { _, old ->
        val now = System.currentTimeMillis()
        val failed = old != null && old.job.isCompleted && runCatching { old.job.getCompleted() }.getOrNull() == null
        if (old == null || failed || now - old.at > KeepMs) Entry(now, scope.async { running.withPermit { resolve(url) } }) else old
    }!!.job

    private suspend fun resolve(url: String): Streams? {
        val command = TrailerTools.ytdlpCommand() + listOf("-J", "--no-playlist", "--no-warnings", "-f", TrailerTools.Format, url)
        val process = runCatching { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() ?: return null
        try {
            runCatching { process.outputStream.close() }
            // Read while waiting, so a large answer can't fill the pipe and stall yt-dlp.
            val output = scope.async { runCatching { process.inputStream.readAllBytes().decodeToString() }.getOrNull() }
            val finished = process.waitFor(TimeoutSeconds, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return null }
            val json = output.await()
            return if (process.exitValue() == 0 && json != null) parse(json) else null
        } finally {
            process.destroyForcibly()
        }
    }

    /** The streams in yt-dlp's `-J` answer: the chosen picture and sound, or the one stream with both. */
    internal fun parse(json: String): Streams? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun JsonObject.agent() = (this["http_headers"] as? JsonObject)?.text("User-Agent")
        val chosen = (root["requested_formats"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (chosen.isNotEmpty()) {
            val picture = chosen.firstOrNull { it.text("vcodec") != "none" } ?: return@runCatching null
            val sound = chosen.firstOrNull { it !== picture && it.text("vcodec") == "none" }
            Streams(picture.text("url") ?: return@runCatching null, sound?.text("url"), picture.agent() ?: root.agent())
        } else root.text("url")?.let { Streams(it, null, root.agent()) }
    }.getOrNull()
}
