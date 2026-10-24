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
 * Finds a YouTube trailer's streams with yt-dlp when it is played. Lookups happen only on play:
 * looking trailers up ahead (on opening a title page, or pointing at a card) asked YouTube so
 * often that it began refusing this connection as a bot. Each lookup is kept for a while, so
 * playing the same trailer again starts at once; YouTube's links last hours.
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

    /** Set when YouTube last refused a lookup as a bot ("Sign in to confirm you're not a bot", or too many requests). */
    @Volatile private var blockedAt = 0L
    private const val BlockedMs = 15 * 60_000L

    /** Whether YouTube refused this connection recently, so trying again (or letting mpv try) won't help yet. */
    fun blocked(): Boolean = blockedAt != 0L && System.currentTimeMillis() - blockedAt < BlockedMs

    /** What a trailer error says when YouTube is refusing this connection. */
    const val BlockedMessage = "YouTube is refusing trailers from this connection for now (it asked to confirm you're not a bot). Try again later."

    /** Whether yt-dlp's error output says YouTube refused it as a bot or for too many requests. */
    internal fun isBotRefusal(stderr: String): Boolean =
        stderr.contains("not a bot", ignoreCase = true) || stderr.contains("HTTP Error 429", ignoreCase = true) ||
            stderr.contains("Too Many Requests", ignoreCase = true)

    private const val KeepMs = 30 * 60_000L
    private const val TimeoutSeconds = 60L
    private val entries = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One lookup at a time: YouTube is quick to refuse a burst.
    private val running = Semaphore(1)

    /** [url]'s streams: the lookup already made or underway, else a new one. Null when yt-dlp couldn't find them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun streams(url: String): Deferred<Streams?> = entries.compute(url) { _, old ->
        val now = System.currentTimeMillis()
        val failed = old != null && old.job.isCompleted && runCatching { old.job.getCompleted() }.getOrNull() == null
        if (old == null || failed || now - old.at > KeepMs) Entry(now, scope.async { running.withPermit { resolve(url) } }) else old
    }!!.job

    private suspend fun resolve(url: String): Streams? {
        val command = TrailerTools.ytdlpCommand() + listOf("-J", "--no-playlist", "--no-warnings", "-f", TrailerTools.Format, url)
        val process = runCatching { ProcessBuilder(command).start() }.getOrNull() ?: return null
        try {
            runCatching { process.outputStream.close() }
            // Read both while waiting, so a large answer can't fill a pipe and stall yt-dlp.
            val output = scope.async { runCatching { process.inputStream.readAllBytes().decodeToString() }.getOrNull() }
            val errors = scope.async { runCatching { process.errorStream.readAllBytes().decodeToString() }.getOrDefault("") }
            val finished = process.waitFor(TimeoutSeconds, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return null }
            val json = output.await()
            val streams = if (process.exitValue() == 0 && json != null) parse(json) else null
            if (streams == null && isBotRefusal(errors.await())) blockedAt = System.currentTimeMillis()
            if (streams != null) blockedAt = 0L
            return streams
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
