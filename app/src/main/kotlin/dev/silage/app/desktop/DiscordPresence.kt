package dev.silage.app.desktop

import dev.silage.player.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

/**
 * The Discord application Silage's status is shown under ("Watching Silage"). Its Rich Presence
 * art should have an image named [DiscordLargeImage]. Blank turns the feature off everywhere.
 * `-Dsilage.discordClientId=…` overrides it for testing.
 */
internal val DiscordClientId: String = System.getProperty("silage.discordClientId")?.trim()?.ifEmpty { null } ?: BuiltInDiscordClientId

/** Silage's own Discord application id. */
private const val BuiltInDiscordClientId = "1570071950758383726"

internal const val DiscordLargeImage = "silage"

/** What Discord shows while a video plays. */
internal data class PresenceInfo(
    val title: String,
    val line: String?,
    val seriesTitle: String? = null,
    val inParty: Boolean = false,
    /** The poster to show instead of Silage's icon; used only when [discordImageUrl] accepts it. */
    val artworkUrl: String? = null,
)

/**
 * [url] when Discord can load it itself: an https link of a sensible length on a public host.
 * LAN names and private or loopback addresses would only show a broken image.
 */
internal fun discordImageUrl(url: String?): String? {
    val u = url?.trim()?.takeIf { it.length <= 300 } ?: return null
    val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
    if (host == "localhost" || '.' !in host && ':' !in host) return null
    if (listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localhost").any { host.endsWith(it) }) return null
    val ipv4 = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && host.count { c -> c == '.' } == 3 }
    if (ipv4 != null) {
        val (a, b) = ipv4
        if (a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)) return null
    }
    if (host.startsWith("[") || ':' in host) return null // IPv6 literals: rarely public, never worth the risk
    return u
}

/**
 * The activity for [state] at [nowMs], or null to show nothing (before the file loads).
 * An episode shows the series on top and "S1 E3 · Episode" under it; a film shows its title
 * and year. While playing, start and end times let Discord draw the elapsed and remaining time.
 */
internal fun presenceActivity(info: PresenceInfo, state: PlayerState, nowMs: Long): JsonObject? {
    if (!state.loaded) return null
    val series = info.seriesTitle
    val details = series ?: info.title
    val under = if (series != null) {
        val numbers = info.line?.removePrefix(series)?.removePrefix(" · ")?.ifBlank { null }
        listOfNotNull(numbers, info.title.takeIf { it.isNotBlank() && it != series }).joinToString(" · ")
    } else info.line.orEmpty()
    val state2 = listOfNotNull(
        under.ifBlank { null },
        "Watch Party".takeIf { info.inParty },
        "Paused".takeIf { state.paused },
    ).joinToString(" · ")
    return buildJsonObject {
        put("type", 3) // Watching
        put("details", discordText(details))
        if (state2.isNotEmpty()) put("state", discordText(state2))
        if (!state.paused) presenceTimes(state, nowMs)?.let { (start, end) ->
            putJsonObject("timestamps") {
                put("start", start)
                end?.let { put("end", it) }
            }
        }
        putJsonObject("assets") {
            val poster = discordImageUrl(info.artworkUrl)
            if (poster != null) {
                put("large_image", poster)
                put("large_text", discordText(details))
                put("small_image", DiscordLargeImage)
                put("small_text", "Silage")
            } else {
                put("large_image", DiscordLargeImage)
                put("large_text", "Silage")
            }
        }
    }
}

/** When this video "started" and will end, in Unix milliseconds, given where it is now. */
internal fun presenceTimes(state: PlayerState, nowMs: Long): Pair<Long, Long?>? {
    if (state.positionSeconds < 0) return null
    val start = nowMs - (state.positionSeconds * 1000).toLong()
    val end = state.durationSeconds.takeIf { it > 0 && it >= state.positionSeconds }?.let { start + (it * 1000).toLong() }
    return start to end
}

/** Discord takes 2 to 128 characters. */
internal fun discordText(s: String): String {
    val t = s.trim()
    return when {
        t.length > 128 -> t.take(127) + "…"
        t.length < 2 -> t.padEnd(2, ' ')
        else -> t
    }
}

/** Discord's local IPC framing: opcode and length (little-endian int32s), then UTF-8 JSON. */
internal object DiscordFrames {
    const val HANDSHAKE = 0
    const val FRAME = 1
    const val CLOSE = 2

    fun encode(op: Int, json: String): ByteArray {
        val body = json.encodeToByteArray()
        return ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op).putInt(body.size).put(body).array()
    }

    fun header(bytes: ByteArray): Pair<Int, Int> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return b.int to b.int
    }

    fun handshake(clientId: String) = encode(HANDSHAKE, buildJsonObject { put("v", 1); put("client_id", clientId) }.toString())

    fun setActivity(pid: Long, activity: JsonObject?, nonce: String = UUID.randomUUID().toString()) = encode(FRAME, buildJsonObject {
        put("cmd", "SET_ACTIVITY")
        putJsonObject("args") {
            put("pid", pid)
            if (activity != null) put("activity", activity) else put("activity", JsonNull)
        }
        put("nonce", nonce)
    }.toString())
}

/** A connection to the Discord app on this computer. */
internal interface DiscordPipe : Closeable {
    fun write(bytes: ByteArray)
    fun readFully(n: Int): ByteArray
}

/** Sends a frame and reads Discord's reply, so the pipe never fills up. Returns the reply's opcode. */
internal fun DiscordPipe.exchange(frame: ByteArray): Int {
    write(frame)
    val (op, length) = DiscordFrames.header(readFully(8))
    if (length in 1..1_000_000) readFully(length)
    return op
}

/** Where Discord listens: named pipes on Windows; sockets in the runtime directory on Linux, Flatpak and Snap included. */
internal fun discordPipePaths(windows: Boolean, env: Map<String, String> = System.getenv()): List<String> =
    if (windows) (0..9).map { """\\.\pipe\discord-ipc-$it""" }
    else {
        val bases = listOfNotNull(env["XDG_RUNTIME_DIR"], env["TMPDIR"], env["TMP"], env["TEMP"], "/tmp").distinct()
        bases.flatMap { base -> listOf("", "app/com.discordapp.Discord/", "snap.discord/").map { "$base/$it" } }
            .flatMap { dir -> (0..9).map { "${dir}discord-ipc-$it" } }
    }

private fun openPipe(): DiscordPipe? {
    val windows = System.getProperty("os.name").startsWith("Windows")
    for (path in discordPipePaths(windows)) {
        val pipe = runCatching {
            if (windows) {
                val file = RandomAccessFile(path, "rw")
                object : DiscordPipe {
                    override fun write(bytes: ByteArray) = file.write(bytes)
                    override fun readFully(n: Int) = ByteArray(n).also { file.readFully(it) }
                    override fun close() = file.close()
                }
            } else {
                if (!Files.exists(Path.of(path))) return@runCatching null
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                runCatching { channel.connect(UnixDomainSocketAddress.of(path)) }.onFailure { channel.close() }.getOrThrow()
                object : DiscordPipe {
                    override fun write(bytes: ByteArray) {
                        val b = ByteBuffer.wrap(bytes)
                        while (b.hasRemaining()) channel.write(b)
                    }
                    override fun readFully(n: Int): ByteArray {
                        val b = ByteBuffer.allocate(n)
                        while (b.hasRemaining()) if (channel.read(b) < 0) throw java.io.EOFException()
                        return b.array()
                    }
                    override fun close() = channel.close()
                }
            }
        }.getOrNull()
        if (pipe != null) return pipe
    }
    return null
}

/**
 * Shows what's playing as a Discord status, while one video is open. Only made when the
 * person turned it on in Settings. Does nothing when Discord isn't running, and looks for it
 * again every [retryMs]; clears the status when closed.
 */
internal class DiscordPresence(
    private val state: StateFlow<PlayerState>,
    info: PresenceInfo,
    /** Looks up a poster when [info] has none Discord can load; the status updates once it arrives. */
    private val artwork: (suspend () -> String?)? = null,
    private val clientId: String = DiscordClientId,
    private val connect: () -> DiscordPipe? = ::openPipe,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryMs: Long = 30_000,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pid = ProcessHandle.current().pid()
    @Volatile private var pipe: DiscordPipe? = null
    @Volatile private var info: PresenceInfo = info
    @Volatile private var artChanged = false
    private val job: Job? = if (clientId.isNotBlank()) scope.launch {
        if (artwork != null && discordImageUrl(info.artworkUrl) == null) launch {
            runCatching { artwork.invoke() }.getOrNull()?.let(::discordImageUrl)?.let { url ->
                this@DiscordPresence.info = this@DiscordPresence.info.copy(artworkUrl = url)
                artChanged = true
            }
        }
        run()
    } else null

    private suspend fun run() {
        var sent: Sent? = null
        var lastSendMs = Long.MIN_VALUE / 2
        var lastTryMs = Long.MIN_VALUE / 2
        while (currentCoroutineContext().isActive) {
            val now = clock()
            if (pipe == null && now - lastTryMs >= retryMs) {
                lastTryMs = now
                pipe = runCatching { connect() }.getOrNull()?.let { p ->
                    val accepted = runCatching { p.exchange(DiscordFrames.handshake(clientId)) != DiscordFrames.CLOSE }.getOrDefault(false)
                    if (accepted) p else { runCatching { p.close() }; null }
                }
                sent = null
            }
            val p = pipe
            if (p != null) {
                val s = state.value
                val next = Sent(s.loaded, s.paused, presenceTimes(s, now)?.first)
                // Discord allows about 5 updates in 20 seconds: send changes, at most one each 4 s.
                if ((presenceChanged(sent, next) || artChanged) && now - lastSendMs >= 4_000) {
                    val art = artChanged
                    val ok = runCatching { p.exchange(DiscordFrames.setActivity(pid, presenceActivity(info, s, now))) != DiscordFrames.CLOSE }.getOrDefault(false)
                    if (ok) { sent = next; lastSendMs = now; if (art) artChanged = false } else drop()
                }
            }
            delay(1_000)
        }
    }

    private fun drop() {
        pipe?.let { runCatching { it.close() } }
        pipe = null
    }

    /** Returns at once; the status is cleared in the background once the update loop has stopped. */
    override fun close() {
        val running = job ?: return
        running.cancel()
        Thread({
            runBlocking { running.join() }
            // Clear the status now rather than leaving it until Discord notices Silage is gone.
            pipe?.let { p -> runCatching { p.exchange(DiscordFrames.setActivity(pid, null)) } }
            drop()
        }, "discord-presence-clear").apply { isDaemon = true }.start()
    }

    /** For tests: waits until [close] has finished clearing the status. */
    internal fun awaitClosed(timeoutMs: Long = 5_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while ((job?.isActive == true || pipe != null) && System.currentTimeMillis() < end) Thread.sleep(10)
    }
}

internal data class Sent(val loaded: Boolean, val paused: Boolean, val startMs: Long?)

/** Worth telling Discord: loaded or paused changed, or the position moved (a seek) by over 3 s. */
internal fun presenceChanged(old: Sent?, new: Sent): Boolean {
    if (old == null) return true
    if (old.loaded != new.loaded || old.paused != new.paused) return true
    val a = old.startMs; val b = new.startMs
    if (new.paused) return false
    return (a == null) != (b == null) || (a != null && b != null && abs(a - b) > 3_000)
}
