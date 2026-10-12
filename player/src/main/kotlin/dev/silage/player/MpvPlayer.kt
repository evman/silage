package dev.silage.player

import com.sun.jna.Pointer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One mpv instance. Knows nothing about Silo: callers hand it URLs and
 * headers. Events are read on a dedicated thread; every public method is
 * safe to call from any thread (libmpv's API is thread-safe).
 */
class MpvPlayer(
    options: Map<String, String> = emptyMap(),
    private val lib: LibMpv = LibMpv.load(),
) : AutoCloseable {

    private val ctx: Pointer = checkNotNull(lib.mpv_create()) { "mpv_create failed" }
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    @Volatile private var closed = false
    private val eventThread: Thread

    init {
        val defaults = mapOf(
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "yes",
            "osc" to "no",
            "idle" to "yes",
            "keep-open" to "no",
            "terminal" to "no",
        )
        (defaults + options).forEach { (k, v) -> lib.mpv_set_option_string(ctx, k, v) }
        val initialized = lib.mpv_initialize(ctx)
        if (initialized < 0) {
            lib.mpv_terminate_destroy(ctx)
            error("mpv_initialize failed: ${lib.mpv_error_string(initialized)}")
        }
        lib.mpv_observe_property(ctx, 1, "time-pos", LibMpv.FORMAT_DOUBLE)
        lib.mpv_observe_property(ctx, 2, "duration", LibMpv.FORMAT_DOUBLE)
        lib.mpv_observe_property(ctx, 3, "pause", LibMpv.FORMAT_FLAG)
        lib.mpv_observe_property(ctx, 4, "paused-for-cache", LibMpv.FORMAT_FLAG)
        eventThread = Thread(::eventLoop, "mpv-events").apply { isDaemon = true; start() }
    }

    fun load(url: String, headers: Map<String, String> = emptyMap(), startSeconds: Double? = null) {
        // change-list append takes one raw entry per call, so commas and colons
        // inside header values need no escaping (a joined string would split).
        command("change-list", "http-header-fields", "clr", "")
        headers.forEach { (k, v) -> command("change-list", "http-header-fields", "append", "$k: $v") }
        setProperty("start", startSeconds?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "none")
        _state.update { PlayerState(paused = it.paused) }
        command("loadfile", url, "replace")
    }

    fun setPaused(paused: Boolean) { setProperty("pause", if (paused) "yes" else "no") }
    fun togglePause() { command("cycle", "pause") }
    fun seekAbsolute(seconds: Double) { command("seek", seconds.toString(), "absolute+exact") }
    fun seekRelative(seconds: Double) { command("seek", seconds.toString(), "relative+exact") }
    fun stop() { command("stop") }

    fun command(vararg args: String): Int = if (closed) -1 else lib.mpv_command(ctx, arrayOf(*args))
    fun setProperty(name: String, value: String): Int = if (closed) -1 else lib.mpv_set_property_string(ctx, name, value)

    fun getProperty(name: String): String? {
        if (closed) return null
        val p = lib.mpv_get_property_string(ctx, name) ?: return null
        return try { p.getString(0, "UTF-8") } finally { lib.mpv_free(p) }
    }

    /**
     * Shows a premultiplied BGRA bitmap at [address] (native memory owned by
     * the caller, valid only for the duration of this call: mpv copies it).
     */
    fun addOverlay(id: Int, address: Long, width: Int, height: Int, displayWidth: Int, displayHeight: Int): Int =
        command(
            "overlay-add", id.toString(), "0", "0", "&$address", "0", "bgra",
            width.toString(), height.toString(), (width * 4).toString(),
            displayWidth.toString(), displayHeight.toString(),
        )

    private fun eventLoop() {
        while (!closed) {
            val ev = lib.mpv_wait_event(ctx, -1.0)
            when (ev.event_id) {
                LibMpv.EVENT_NONE -> Unit
                LibMpv.EVENT_SHUTDOWN -> { _events.tryEmit(PlayerEvent.Shutdown); return }
                LibMpv.EVENT_FILE_LOADED -> {
                    _state.update { it.copy(loaded = true) }
                    _events.tryEmit(PlayerEvent.FileLoaded)
                }
                LibMpv.EVENT_PLAYBACK_RESTART -> _events.tryEmit(PlayerEvent.PlaybackRestart)
                LibMpv.EVENT_END_FILE -> ev.data?.let { p ->
                    val end = MpvEventEndFile(p)
                    val reason = EndReason.of(end.reason)
                    _state.update { it.copy(loaded = false) }
                    _events.tryEmit(PlayerEvent.Ended(reason, if (reason == EndReason.ERROR) lib.mpv_error_string(end.error) else null))
                }
                LibMpv.EVENT_CLIENT_MESSAGE -> ev.data?.let { p ->
                    _events.tryEmit(PlayerEvent.ClientMessage(MpvEventClientMessage(p).strings()))
                }
                LibMpv.EVENT_PROPERTY_CHANGE -> ev.data?.let { p -> onProperty(MpvEventProperty(p)) }
            }
        }
    }

    private fun onProperty(prop: MpvEventProperty) {
        val data = prop.data ?: return
        when (prop.name) {
            "time-pos" -> if (prop.format == LibMpv.FORMAT_DOUBLE) _state.update { it.copy(positionSeconds = data.getDouble(0)) }
            "duration" -> if (prop.format == LibMpv.FORMAT_DOUBLE) _state.update { it.copy(durationSeconds = data.getDouble(0)) }
            "pause" -> if (prop.format == LibMpv.FORMAT_FLAG) _state.update { it.copy(paused = data.getInt(0) != 0) }
            "paused-for-cache" -> if (prop.format == LibMpv.FORMAT_FLAG) _state.update { it.copy(buffering = data.getInt(0) != 0) }
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        lib.mpv_wakeup(ctx)
        eventThread.join(2_000)
        lib.mpv_terminate_destroy(ctx)
    }
}
