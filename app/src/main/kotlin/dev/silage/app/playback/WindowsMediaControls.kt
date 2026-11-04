package dev.silage.app.playback

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import dev.silage.app.playback.hud.MediaKey
import dev.silage.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/** What the Windows media overlay shows for the video. */
internal data class MediaControlsInfo(val title: String, val subtitle: String?, val artworkUrl: String? = null)

/** The overlay's playback status for a player state (MediaPlaybackStatus). */
internal fun mediaPlaybackStatus(s: PlayerState): Int = when {
    !s.loaded -> 1
    s.paused -> 4
    else -> 3
}

/** WinRT's TimeSpan counts 100-nanosecond ticks. */
internal fun winRtTicks(seconds: Double): Long = (seconds.coerceAtLeast(0.0) * 10_000_000).toLong()

/**
 * The Windows media overlay (the volume flyout, quick settings, the lock
 * screen): the title, play and pause, previous and next, stop, and a position
 * bar, through System Media Transport Controls. Java has no WinRT projection,
 * so this calls the COM interfaces by their slots in the SDK's windows.media.h.
 * Any failure just leaves Silage without the overlay.
 */
internal class WindowsMediaControls(
    window: java.awt.Window?,
    info: MediaControlsInfo,
    state: StateFlow<PlayerState>,
    private val onKey: (MediaKey) -> Unit,
    private val setPaused: (Boolean) -> Unit,
    private val seekTo: (Double) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "silage-media-controls").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private var controls: Pointer? = null
    private var controls2: Pointer? = null
    private var buttonToken = 0L
    private var positionToken = 0L
    // Windows may still hold them after close, so they live as long as the process.
    private val buttonHandler = ComHandler(ButtonHandlerIid) { args ->
        val button = IntByReference()
        if (Com.call(args, 6, button) >= 0) when (button.value) {
            ButtonPlay -> setPaused(false)
            ButtonPause -> setPaused(true)
            ButtonStop -> onKey(MediaKey.Stop)
            ButtonNext -> onKey(MediaKey.Next)
            ButtonPrevious -> onKey(MediaKey.Previous)
        }
    }
    private val positionHandler = ComHandler(PositionHandlerIid) { args ->
        val ticks = LongByReference()
        if (Com.call(args, 6, ticks) >= 0) seekTo(ticks.value / 10_000_000.0)
    }

    init {
        scope.launch {
            runCatching {
                Winrt.initialize()
                start(window ?: error("no window"), info)
                var status = -1
                var shownAt = Double.NaN
                var duration = 0.0
                state.collect { s ->
                    val next = mediaPlaybackStatus(s)
                    if (next != status) { status = next; Com.check(Com.call(controls!!, 7, next)) }
                    if (s.durationSeconds != duration || shownAt.isNaN() || abs(s.positionSeconds - shownAt) >= TimelineEverySeconds) {
                        duration = s.durationSeconds
                        shownAt = s.positionSeconds
                        updateTimeline(s)
                    }
                }
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) System.err.println("Silage: Windows media controls unavailable: $it")
            }
        }
    }

    private fun start(window: java.awt.Window, info: MediaControlsInfo) {
        val hwnd = Native.getComponentPointer(window) ?: error("the window has no handle yet")
        val interop = Winrt.factory("Windows.Media.SystemMediaTransportControls", InteropIid)
        val smtc = try {
            Com.out { Com.call(interop, 6, hwnd, Com.guid(ControlsIid), it) }
        } finally { Com.release(interop) }
        controls = smtc
        controls2 = Com.queryInterface(smtc, Controls2Iid)
        Com.check(Com.call(smtc, 11, 1.toByte()))
        listOf(SlotPlayEnabled, SlotPauseEnabled, SlotStopEnabled, SlotPreviousEnabled, SlotNextEnabled).forEach { Com.check(Com.call(smtc, it, 1.toByte())) }
        LongByReference().let { Com.check(Com.call(smtc, 32, buttonHandler.pointer, it)); buttonToken = it.value }
        controls2?.let { c2 -> LongByReference().let { Com.check(Com.call(c2, 13, positionHandler.pointer, it)); positionToken = it.value } }
        showInfo(smtc, info)
    }

    private fun showInfo(smtc: Pointer, info: MediaControlsInfo) {
        val updater = Com.out { Com.call(smtc, 8, it) }
        try {
            Com.check(Com.call(updater, 7, TypeVideo))
            val video = Com.out { Com.call(updater, 13, it) }
            try {
                Winrt.string(info.title) { Com.check(Com.call(video, 7, it)) }
                info.subtitle?.let { line -> Winrt.string(line) { Com.check(Com.call(video, 9, it)) } }
            } finally { Com.release(video) }
            info.artworkUrl?.let { url -> runCatching { setArtwork(updater, url) } }
            Com.check(Com.call(updater, 17))
        } finally { Com.release(updater) }
    }

    private fun setArtwork(updater: Pointer, url: String) {
        val uriFactory = Winrt.factory("Windows.Foundation.Uri", UriFactoryIid)
        val uri = try { Winrt.stringResult(url) { s, out -> Com.call(uriFactory, 6, s, out) } } finally { Com.release(uriFactory) }
        try {
            val statics = Winrt.factory("Windows.Storage.Streams.RandomAccessStreamReference", StreamReferenceStaticsIid)
            try {
                val reference = Com.out { Com.call(statics, 7, uri, it) }
                try { Com.check(Com.call(updater, 11, reference)) } finally { Com.release(reference) }
            } finally { Com.release(statics) }
        } finally { Com.release(uri) }
    }

    private fun updateTimeline(s: PlayerState) {
        val c2 = controls2 ?: return
        val timeline = Winrt.activate("Windows.Media.SystemMediaTransportControlsTimelineProperties", TimelineIid)
        try {
            val end = winRtTicks(s.durationSeconds)
            Com.check(Com.call(timeline, 7, 0L))
            Com.check(Com.call(timeline, 9, end))
            Com.check(Com.call(timeline, 11, 0L))
            Com.check(Com.call(timeline, 13, end))
            Com.check(Com.call(timeline, 15, winRtTicks(s.positionSeconds)))
            Com.check(Com.call(c2, 12, timeline))
        } finally { Com.release(timeline) }
    }

    override fun close() {
        scope.cancel()
        executor.execute {
            runCatching {
                controls?.let { smtc ->
                    if (buttonToken != 0L) Com.call(smtc, 33, buttonToken)
                    controls2?.let { c2 -> if (positionToken != 0L) Com.call(c2, 14, positionToken) }
                    Com.out { Com.call(smtc, 8, it) }.let { updater -> Com.call(updater, 16); Com.call(updater, 17); Com.release(updater) }
                    Com.call(smtc, 7, StatusClosed)
                    Com.call(smtc, 11, 0.toByte())
                    controls2?.let(Com::release)
                    Com.release(smtc)
                }
            }
        }
        executor.shutdown()
    }

    private companion object {
        // From the SDK's windows.media.h, windows.foundation.h and windows.storage.streams.h.
        const val ControlsIid = "99fa3ff4-1742-42a6-902e-087d41f965ec"
        const val Controls2Iid = "ea98d2f6-7f3c-4af2-a586-72889808efb1"
        const val InteropIid = "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"
        const val TimelineIid = "5125316a-c3a2-475b-8507-93534dc88f15"
        const val ButtonHandlerIid = "0557e996-7b23-5bae-aa81-ea0d671143a4"
        const val PositionHandlerIid = "44e34f15-bdc0-50a7-ace4-39e91fb753f1"
        const val UriFactoryIid = "44a9796f-723e-4fdf-a218-033e75b0c084"
        const val StreamReferenceStaticsIid = "857309dc-3fbf-4e7d-986f-ef3b1a07a964"

        const val SlotPlayEnabled = 13
        const val SlotStopEnabled = 15
        const val SlotPauseEnabled = 17
        const val SlotPreviousEnabled = 25
        const val SlotNextEnabled = 27

        const val ButtonPlay = 0
        const val ButtonPause = 1
        const val ButtonStop = 2
        const val ButtonNext = 6
        const val ButtonPrevious = 7

        const val TypeVideo = 2
        const val StatusClosed = 0
        const val TimelineEverySeconds = 5.0
    }
}

/** Calling COM interfaces by vtable slot (x64 has one calling convention). */
internal object Com {
    fun call(self: Pointer, slot: Int, vararg args: Any?): Int =
        Function.getFunction(self.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)).invokeInt(arrayOf(self, *args))

    fun check(hresult: Int) { if (hresult < 0) error("HRESULT 0x%08X".format(hresult)) }

    /** Calls a method whose last parameter receives an interface. */
    fun out(call: (PointerByReference) -> Int): Pointer {
        val out = PointerByReference()
        check(call(out))
        return out.value ?: error("no interface returned")
    }

    fun queryInterface(self: Pointer, iid: String): Pointer = out { call(self, 0, guid(iid), it) }

    fun release(self: Pointer) { call(self, 2) }

    fun guid(text: String): Memory {
        val id = UUID.fromString(text)
        val high = id.mostSignificantBits
        return Memory(16).apply {
            // A GUID's first three fields are little-endian, its last eight bytes in order.
            setInt(0, (high ushr 32).toInt())
            setShort(4, (high ushr 16).toShort())
            setShort(6, high.toShort())
            for (i in 0..7) setByte(8L + i, (id.leastSignificantBits ushr (56 - 8 * i)).toByte())
        }
    }
}

/** The WinRT runtime's own entry points. */
internal object Winrt {
    private interface Combase : StdCallLibrary {
        fun RoInitialize(type: Int): Int
        fun WindowsCreateString(text: WString, length: Int, out: PointerByReference): Int
        fun WindowsDeleteString(string: Pointer?): Int
        fun RoGetActivationFactory(classId: Pointer, iid: Pointer, out: PointerByReference): Int
        fun RoActivateInstance(classId: Pointer, out: PointerByReference): Int
    }

    private val combase by lazy { Native.load("combase", Combase::class.java) }

    fun initialize() { combase.RoInitialize(1) }

    fun <T> string(text: String, use: (Pointer?) -> T): T {
        val out = PointerByReference()
        Com.check(combase.WindowsCreateString(WString(text), text.length, out))
        try { return use(out.value) } finally { combase.WindowsDeleteString(out.value) }
    }

    fun stringResult(text: String, call: (Pointer?, PointerByReference) -> Int): Pointer = string(text) { s -> Com.out { call(s, it) } }

    fun factory(className: String, iid: String): Pointer =
        stringResult(className) { s, out -> combase.RoGetActivationFactory(s!!, Com.guid(iid), out) }

    fun activate(className: String, iid: String): Pointer {
        val instance = stringResult(className) { s, out -> combase.RoActivateInstance(s!!, out) }
        try { return Com.queryInterface(instance, iid) } finally { Com.release(instance) }
    }
}

/**
 * A TypedEventHandler for Windows to call: a hand-built COM object. It is never
 * freed, because Windows owns references to it whose end we don't see.
 */
private class ComHandler(iid: String, private val onEvent: (args: Pointer) -> Unit) {
    private interface QueryInterface : Callback { fun callback(self: Pointer, iid: Pointer, out: Pointer): Int }
    private interface RefCount : Callback { fun callback(self: Pointer): Int }
    private interface Invoke : Callback { fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int }

    private val known = listOf(iid, IUnknown, IAgileObject).map { Com.guid(it).getByteArray(0, 16) }

    private val query = object : QueryInterface {
        override fun callback(self: Pointer, iid: Pointer, out: Pointer): Int {
            val asked = iid.getByteArray(0, 16)
            if (known.none { it.contentEquals(asked) }) { out.setPointer(0, null); return E_NOINTERFACE }
            out.setPointer(0, self)
            return 0
        }
    }
    private val count = object : RefCount { override fun callback(self: Pointer): Int = 1 }
    private val invoke = object : Invoke {
        override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
            runCatching { args?.let(onEvent) }.onFailure { System.err.println("Silage: media control failed: $it") }
            return 0
        }
    }
    private val table = Memory(4L * Native.POINTER_SIZE).apply {
        listOf(query, count, count, invoke).forEachIndexed { i, callback ->
            setPointer(i.toLong() * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(callback))
        }
    }
    val pointer: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, table) }

    init { retained += this }

    private companion object {
        const val E_NOINTERFACE = 0x80004002.toInt()
        const val IUnknown = "00000000-0000-0000-c000-000000000046"
        const val IAgileObject = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
        val retained = java.util.Collections.synchronizedList(mutableListOf<ComHandler>())
    }
}
