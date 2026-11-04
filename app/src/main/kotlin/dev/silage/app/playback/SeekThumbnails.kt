package dev.silage.app.playback

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.silage.player.MpvPlayer
import dev.silage.player.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A preview frame for the seek bar, for the [seconds] slot it was taken for. */
class SeekThumbnail(val seconds: Double, val image: ImageBitmap)

/** Previews are spaced by slot: every 10 s, or further apart so a long film needs at most 240 of them. */
fun thumbnailSlot(seconds: Double, duration: Double): Double {
    val interval = maxOf(10.0, duration / 240)
    return (seconds.coerceAtLeast(0.0) / interval).toLong() * interval
}

/** Forgets the frames used longest ago, past [capacity]. */
internal class ThumbnailCache(private val capacity: Int = 64) {
    private val frames = object : LinkedHashMap<Double, SeekThumbnail>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Double, SeekThumbnail>) = size > capacity
    }

    operator fun get(slot: Double): SeekThumbnail? = frames[slot]
    fun put(frame: SeekThumbnail) { frames[frame.seconds] = frame }
}

/**
 * Makes seek bar previews on the fly, as the server has no ready-made ones:
 * a second, invisible mpv opens the same file on first use and jumps from
 * keyframe to keyframe, without touching what is playing. Only the newest
 * request counts, so sweeping the pointer never builds a queue. If the file
 * can't be read this way, it stops and the bar goes on showing just the time.
 */
class SeekThumbnails(
    private val url: String,
    private val headers: Map<String, String>,
    private val onFrame: (SeekThumbnail) -> Unit,
    private val open: (Map<String, String>) -> MpvPlayer = { MpvPlayer(it) },
) : AutoCloseable {
    private val wanted = AtomicReference<Double?>(null)
    private val signal = java.util.concurrent.Semaphore(0)
    private val cache = ThumbnailCache()
    @Volatile private var closed = false
    private val worker = Thread(::run, "silage-thumbnails").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    private var started = false

    /** Asks for the preview of [slot] (from [thumbnailSlot]). */
    @Synchronized
    fun request(slot: Double) {
        if (closed) return
        wanted.set(slot)
        signal.release()
        if (!started) { started = true; worker.start() }
    }

    private fun run() {
        val folder = Files.createTempDirectory("silage-thumbnails")
        val file = folder.resolve("frame.jpg")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var mpv: MpvPlayer? = null
        val events = LinkedBlockingQueue<PlayerEvent>()
        var failures = 0
        try {
            while (!closed && failures < MaxFailures) {
                signal.acquire()
                signal.drainPermits()
                val slot = wanted.getAndSet(null) ?: continue
                val known = cache[slot]
                if (known != null) { onFrame(known); continue }
                val ready = if (mpv == null) {
                    mpv = open(Options)
                    val opened = mpv
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { opened.events.collect { events.put(it) } }
                    opened.load(url, headers, slot)
                    awaitFrame(events, FirstFrameTimeoutMs) && toneMapIfHdr(opened, events, slot)
                } else {
                    events.clear()
                    mpv.command("seek", "%.3f".format(java.util.Locale.ROOT, slot), "absolute+keyframes") >= 0 && awaitFrame(events, SeekTimeoutMs)
                }
                val frame = if (ready) capture(mpv, file, slot) else null
                if (frame == null) { failures++; continue }
                failures = 0
                cache.put(frame)
                if (!closed) onFrame(frame)
            }
        } catch (_: InterruptedException) {
            // Closed while waiting.
        } catch (t: Throwable) {
            System.err.println("Silage: seek previews stopped: $t")
        } finally {
            scope.cancel()
            runCatching { mpv?.close() }
            runCatching { Files.deleteIfExists(file); Files.deleteIfExists(folder) }
        }
    }

    private fun awaitFrame(events: LinkedBlockingQueue<PlayerEvent>, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!closed) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return false
            when (val e = events.poll(left, TimeUnit.MILLISECONDS)) {
                is PlayerEvent.PlaybackRestart -> return true
                is PlayerEvent.Ended, is PlayerEvent.Shutdown -> return false
                else -> if (e == null) return false
            }
        }
        return false
    }

    /** An HDR picture would look washed out as an ordinary image. Where mpv can't convert it, it stays as it is. */
    private fun toneMapIfHdr(mpv: MpvPlayer, events: LinkedBlockingQueue<PlayerEvent>, slot: Double): Boolean {
        val gamma = mpv.getProperty("video-params/gamma")
        if (gamma != "pq" && gamma != "hlg") return true
        events.clear()
        if (mpv.command("vf", "set", HdrFilters) < 0) return true
        // The frame on hand went through the old filters: decode it again through the new ones.
        return mpv.command("seek", "%.3f".format(java.util.Locale.ROOT, slot), "absolute+keyframes") >= 0 && awaitFrame(events, SeekTimeoutMs)
    }

    private fun capture(mpv: MpvPlayer, file: java.nio.file.Path, slot: Double): SeekThumbnail? {
        if (mpv.command("screenshot-to-file", file.toString(), "video") < 0) return null
        return runCatching {
            Image.makeFromEncoded(Files.readAllBytes(file)).use { SeekThumbnail(slot, it.toComposeImageBitmap()) }
        }.getOrNull()
    }

    @Synchronized
    override fun close() {
        closed = true
        // A worker never started has nothing to stop; a running one wakes and tidies up by itself.
        if (started) worker.interrupt()
    }

    private companion object {
        const val MaxFailures = 3
        const val FirstFrameTimeoutMs = 20_000L
        const val SeekTimeoutMs = 6_000L
        const val Scale = "scale=320:-2"
        const val HdrFilters = "lavfi=[zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p],$Scale"
        val Options = mapOf(
            "config" to "no", "load-scripts" to "no", "input-vo-keyboard" to "no",
            "vo" to "null", "ao" to "null", "aid" to "no", "sid" to "no", "pause" to "yes", "keep-open" to "always",
            "hwdec" to "auto-copy", "hr-seek" to "no", "vf" to Scale,
            "cache" to "no", "demuxer-max-bytes" to "16MiB", "demuxer-max-back-bytes" to "0", "demuxer-readahead-secs" to "0",
            "screenshot-format" to "jpg", "screenshot-jpeg-quality" to "80",
        )
    }
}
