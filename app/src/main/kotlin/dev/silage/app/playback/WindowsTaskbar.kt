package dev.silage.app.playback

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import dev.silage.app.WindowsAppCommands
import dev.silage.app.playback.hud.MediaKey
import dev.silage.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.Executors

/** The taskbar icon's progress state for a player state (TBPFLAG). */
internal fun taskbarProgressState(s: PlayerState): Int = when {
    !s.loaded || s.durationSeconds <= 0 -> TaskbarProgress.None
    s.paused -> TaskbarProgress.Paused
    else -> TaskbarProgress.Normal
}

internal object TaskbarProgress {
    const val None = 0
    const val Normal = 2
    const val Paused = 8
}

/** Which button a WM_COMMAND is, when it came from the thumbnail toolbar. */
internal fun thumbnailButton(wParam: Long): Int? =
    if (((wParam ushr 16) and 0xFFFF).toInt() == THBN_CLICKED) (wParam and 0xFFFF).toInt() else null

private const val THBN_CLICKED = 0x1800

/**
 * Silage's Windows taskbar entry while a video is open: Previous, Play/Pause and
 * Next under the thumbnail, and the position as a progress bar on the icon.
 *
 * Windows lets a window add these buttons once, ever, so there is one of these
 * per window for its whole life: between videos the buttons are hidden. Any
 * failure just leaves the taskbar as it was.
 */
internal class WindowsTaskbar private constructor(private val window: java.awt.Window) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "silage-taskbar").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var taskbar: Pointer? = null
    private var hwnd: Pointer? = null
    private var added = false
    private var icons: Map<Int, Pointer> = emptyMap()
    @Volatile private var onButton: ((Int) -> Unit)? = null

    private interface User32 : StdCallLibrary {
        fun CreateIconIndirect(info: IconInfo): Pointer?
        fun GetSystemMetrics(index: Int): Int
    }

    private interface Gdi32 : StdCallLibrary {
        fun CreateBitmap(width: Int, height: Int, planes: Int, bitsPerPixel: Int, bits: Pointer?): Pointer?
    }

    private interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, flags: Int): Int
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: PointerByReference): Int
    }

    @Structure.FieldOrder("fIcon", "xHotspot", "yHotspot", "hbmMask", "hbmColor")
    class IconInfo : Structure() {
        @JvmField var fIcon = 1
        @JvmField var xHotspot = 0
        @JvmField var yHotspot = 0
        @JvmField var hbmMask: Pointer? = null
        @JvmField var hbmColor: Pointer? = null
    }

    private fun start(): Boolean {
        val handle = Native.getComponentPointer(window) ?: return false
        hwnd = handle
        val ole = Native.load("ole32", Ole32::class.java)
        ole.CoInitializeEx(null, 2)
        val out = PointerByReference()
        Com.check(ole.CoCreateInstance(Com.guid(ClsidTaskbarList), null, 1, Com.guid(IidTaskbarList3), out))
        taskbar = out.value
        Com.check(Com.call(out.value, SlotHrInit))
        val user32 = Native.load("user32", User32::class.java)
        val size = user32.GetSystemMetrics(SmCxSmIcon).takeIf { it > 0 } ?: 16
        icons = listOf(IconPrevious, IconPlay, IconPause, IconNext).associateWith { icon(user32, it, size) }
        return true
    }

    /** Draws a white glyph and makes a Windows icon of it. */
    private fun icon(user32: User32, kind: Int, size: Int): Pointer {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = Color.WHITE
            val s = size.toDouble()
            fun x(f: Double) = (f * s).toInt()
            fun triangle(a: Double, b: Double) = fillPolygon(Polygon(intArrayOf(x(a), x(b), x(a)), intArrayOf(x(0.15), x(0.5), x(0.85)), 3))
            when (kind) {
                IconPlay -> triangle(0.25, 0.85)
                IconPause -> { fillRect(x(0.22), x(0.15), x(0.2), x(0.7)); fillRect(x(0.58), x(0.15), x(0.2), x(0.7)) }
                IconNext -> { triangle(0.15, 0.68); fillRect(x(0.7), x(0.15), x(0.14), x(0.7)) }
                else -> { fillPolygon(Polygon(intArrayOf(x(0.85), x(0.32), x(0.85)), intArrayOf(x(0.15), x(0.5), x(0.85)), 3)); fillRect(x(0.16), x(0.15), x(0.14), x(0.7)) }
            }
            dispose()
        }
        val gdi = Native.load("gdi32", Gdi32::class.java)
        val pixels = Memory(size.toLong() * size * 4)
        val row = image.getRGB(0, 0, size, size, null, 0, size)
        row.forEachIndexed { i, argb -> pixels.setInt(i * 4L, argb) }
        val info = IconInfo().apply {
            hbmColor = gdi.CreateBitmap(size, size, 1, 32, pixels)
            hbmMask = gdi.CreateBitmap(size, size, 1, 1, Memory(((size + 15) / 16 * 2 * size).toLong()).also { it.clear() })
        }
        return user32.CreateIconIndirect(info) ?: error("CreateIconIndirect failed")
    }

    /** One THUMBBUTTON in its x64 layout: the handle is 8-aligned, and the tooltip is 260 wide characters. */
    private fun fill(array: Memory, index: Int, id: Int, icon: Pointer?, tip: String, hidden: Boolean) {
        val at = index * ButtonSize
        array.setInt(at, MaskIconTipFlags)
        array.setInt(at + 4, id)
        array.setInt(at + 8, 0)
        array.setPointer(at + 16, icon)
        tip.take(259).forEachIndexed { i, c -> array.setChar(at + 24 + i * 2L, c) }
        array.setChar(at + 24 + tip.length.coerceAtMost(259) * 2L, 0.toChar())
        array.setInt(at + 544, if (hidden) FlagHidden else 0)
    }

    private fun buttons(paused: Boolean, hidden: Boolean): Memory = Memory(3L * ButtonSize).also { array ->
        array.clear()
        fill(array, 0, ButtonPrevious, icons[IconPrevious], "Back 10 seconds", hidden)
        fill(array, 1, ButtonPlayPause, icons[if (paused) IconPlay else IconPause], if (paused) "Play" else "Pause", hidden)
        fill(array, 2, ButtonNext, icons[IconNext], "Next", hidden)
    }

    private fun showButtons(paused: Boolean, hidden: Boolean) {
        val bar = taskbar ?: return
        val slot = if (added) SlotUpdateButtons else SlotAddButtons
        Com.check(Com.call(bar, slot, hwnd, 3, buttons(paused, hidden)))
        added = true
    }

    private fun show(state: StateFlow<PlayerState>, onKey: (MediaKey) -> Unit, setPaused: (Boolean) -> Unit): AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        onButton = { id ->
            when (id) {
                ButtonPrevious -> onKey(MediaKey.Previous)
                ButtonPlayPause -> setPaused(!state.value.paused)
                ButtonNext -> onKey(MediaKey.Next)
            }
        }
        scope.launch {
            runCatching {
                var paused: Boolean? = null
                var progress = -1
                var lastPercent = -1L
                state.collect { s ->
                    val bar = taskbar ?: return@collect
                    if (s.paused != paused) { paused = s.paused; showButtons(s.paused, hidden = false) }
                    val next = taskbarProgressState(s)
                    if (next != progress) { progress = next; Com.call(bar, SlotProgressState, hwnd, next) }
                    if (next != TaskbarProgress.None) {
                        val permille = (s.positionSeconds / s.durationSeconds * 1000).toLong().coerceIn(0, 1000)
                        if (permille != lastPercent) { lastPercent = permille; Com.call(bar, SlotProgressValue, hwnd, permille, 1000L) }
                    }
                }
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) System.err.println("Silage: taskbar controls failed: $it")
            }
        }
        return AutoCloseable {
            onButton = null
            scope.cancel()
            executor.execute {
                runCatching {
                    taskbar?.let { bar ->
                        Com.call(bar, SlotProgressState, hwnd, TaskbarProgress.None)
                        if (added) showButtons(paused = true, hidden = true)
                    }
                }
            }
        }
    }

    companion object {
        private val forWindow = java.util.WeakHashMap<java.awt.Window, WindowsTaskbar>()

        /** Adds the controls for a video; closing it takes them away again. */
        fun open(window: java.awt.Window?, state: StateFlow<PlayerState>, onKey: (MediaKey) -> Unit, setPaused: (Boolean) -> Unit): AutoCloseable {
            window ?: return AutoCloseable {}
            return runCatching {
                val bar = synchronized(forWindow) {
                    forWindow.getOrPut(window) {
                        WindowsTaskbar(window).also { t ->
                            t.executor.execute { runCatching { t.start() }.onFailure { System.err.println("Silage: taskbar controls unavailable: $it") } }
                            WindowsAppCommands.onWindowCommand(window) { wParam -> thumbnailButton(wParam)?.let { id -> t.onButton?.invoke(id) } }
                        }
                    }
                }
                bar.show(state, onKey, setPaused)
            }.getOrElse { AutoCloseable {} }
        }

        const val ButtonSize = 552L
        const val MaskIconTipFlags = 2 or 4 or 8
        const val FlagHidden = 8
        const val ButtonPrevious = 1
        const val ButtonPlayPause = 2
        const val ButtonNext = 3
        const val IconPrevious = 0
        const val IconPlay = 1
        const val IconPause = 2
        const val IconNext = 3
        const val SmCxSmIcon = 49

        // From the SDK's shobjidl_core.h.
        const val ClsidTaskbarList = "56fdf344-fd6d-11d0-958a-006097c9a090"
        const val IidTaskbarList3 = "ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf"
        const val SlotHrInit = 3
        const val SlotProgressValue = 9
        const val SlotProgressState = 10
        const val SlotAddButtons = 15
        const val SlotUpdateButtons = 16
    }
}
