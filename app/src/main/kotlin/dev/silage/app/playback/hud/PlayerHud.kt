@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.silage.app.playback.hud

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.sun.jna.Memory
import com.sun.jna.Pointer
import dev.silage.app.playback.PlaybackMeta
import dev.silage.player.MpvPlayer
import dev.silage.player.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Draws [HudContent] off-screen and shows it over the video with mpv's
 * `overlay-add`, at the OSD's own resolution so text stays sharp at 4K.
 * All scene work happens on one thread; input from mpv (mouse, and keys in
 * mpv's own window) and from AWT is queued onto it.
 */
class PlayerHud(private val mpv: MpvPlayer, meta: PlaybackMeta) : AutoCloseable {
    private val ui = mutableStateOf(HudUi(meta = meta))
    private val visibility = HudVisibility(hideAfterMs = 3_000)
    private val inbox = ConcurrentLinkedQueue<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = true

    private val actions = object : HudActions {
        override fun togglePause() { mpv.command("cycle", "pause") }
        override fun seekBy(seconds: Double) { mpv.command("seek", seconds.toString(), "relative+exact") }
        override fun seekTo(seconds: Double) { mpv.command(*HudCommands.seekTo(seconds)) }
        override fun openMenu(menu: HudMenu) {
            val current = ui.value.menu
            ui.value = ui.value.copy(menu = if (current == menu) HudMenu.None else menu)
            if (menu == HudMenu.Audio || menu == HudMenu.Subtitles) refreshTracks()
        }
        override fun audio(id: Int) { mpv.command(*HudCommands.audio(id)); refreshTracks() }
        override fun subtitle(id: Int?) { mpv.command(*HudCommands.subtitle(id)); refreshTracks() }
        override fun speed(value: Double) { mpv.command(*HudCommands.speed(value)); ui.value = ui.value.copy(speed = value) }
        override fun exit() { mpv.command("stop") }
    }

    private val thread = Thread(::loop, "silage-hud").apply { isDaemon = true; start() }

    init {
        // Mouse always comes from mpv: its window sits on top of ours.
        mpv.command("keybind", "MOUSE_MOVE", "script-message silage-mouse")
        mpv.command("keybind", "MBTN_LEFT", "script-message silage-click")
        HudKey.MPV_KEYS.forEach { mpv.command("keybind", it, "script-message silage-key $it") }
        scope.launch {
            mpv.events.filterIsInstance<PlayerEvent.ClientMessage>().collect { m ->
                when (m.args.firstOrNull()) {
                    "silage-mouse" -> inbox += { pointer(PointerEventType.Move) }
                    "silage-click" -> inbox += { pointer(PointerEventType.Press); pointer(PointerEventType.Release) }
                    "silage-key" -> m.args.getOrNull(1)?.let(HudKey::ofMpv)?.let(::key)
                }
            }
        }
    }

    /** Opens (or toggles) a menu, as its HUD button would. */
    fun open(menu: HudMenu) { inbox += { visibility.activity(); actions.openMenu(menu) } }

    /** A key from any source (AWT or mpv). */
    fun key(key: HudKey) {
        inbox += {
            visibility.activity()
            when (val a = HudKeys.action(key, ui.value.menu != HudMenu.None)) {
                HudAction.Exit -> actions.exit()
                HudAction.CloseMenu -> ui.value = ui.value.copy(menu = HudMenu.None)
                HudAction.TogglePause -> actions.togglePause()
                is HudAction.SeekBy -> actions.seekBy(a.seconds)
                HudAction.ToggleInfo -> actions.openMenu(HudMenu.Info)
                HudAction.Show -> Unit
            }
        }
    }

    private var scene: ImageComposeScene? = null
    private var bitmap: Bitmap? = null
    private var native: Memory? = null
    private var overlayShown = false
    private var lastInfoAt = 0L

    private fun pointer(type: PointerEventType) {
        visibility.activity()
        val pos = mousePos() ?: return
        scene?.sendPointerEvent(
            eventType = type,
            position = pos,
            buttons = if (type == PointerEventType.Press) PointerButtons(isPrimaryPressed = true) else PointerButtons(),
            button = if (type == PointerEventType.Move) null else PointerButton.Primary,
        )
    }

    private fun mousePos(): Offset? {
        val x = mpv.getProperty("mouse-pos/x")?.toFloatOrNull() ?: return null
        val y = mpv.getProperty("mouse-pos/y")?.toFloatOrNull() ?: return null
        return Offset(x, y)
    }

    private fun refreshTracks() {
        ui.value = ui.value.copy(tracks = Tracks.parse(mpv.getProperty("track-list") ?: "[]"))
    }

    private fun loop() {
        while (running) {
            try {
                tick()
            } catch (t: Throwable) {
                System.err.println("silage-hud: ${t.message}")
            }
            Thread.sleep(16)
        }
    }

    private fun tick() {
        val w = mpv.getProperty("osd-width")?.toIntOrNull() ?: 0
        val h = mpv.getProperty("osd-height")?.toIntOrNull() ?: 0
        if (w <= 0 || h <= 0) return
        ensureScene(w, h)
        while (true) inbox.poll()?.invoke() ?: break

        val s = mpv.state.value
        val now = System.currentTimeMillis()
        val menuOpen = ui.value.menu != HudMenu.None
        val visible = visibility.visible(now, s.paused, menuOpen)
        val info = if (ui.value.menu == HudMenu.Info && now - lastInfoAt > 1_000) {
            lastInfoAt = now
            InfoRows.build(mpv::getProperty)
        } else {
            ui.value.info
        }
        // Quantized so a static HUD doesn't redraw every frame.
        val next = ui.value.copy(
            position = if (visible) (s.positionSeconds * 4).toLong() / 4.0 else ui.value.position,
            duration = s.durationSeconds,
            bufferedTo = if (visible) mpv.getProperty("demuxer-cache-time")?.toDoubleOrNull() ?: 0.0 else ui.value.bufferedTo,
            paused = s.paused,
            buffering = s.buffering,
            visible = visible,
            info = info,
            skip = SkipPrompt.at(s.positionSeconds, ui.value.meta.intro, ui.value.meta.credits),
        )
        if (next != ui.value) ui.value = next

        val sc = scene ?: return
        if (sc.hasInvalidations() || !overlayShown && visible) {
            render(sc, w, h)
        } else if (!visible && next.skip == null && !next.buffering && overlayShown && !sc.hasInvalidations()) {
            mpv.command("overlay-remove", "0")
            overlayShown = false
        }
    }

    private fun ensureScene(w: Int, h: Int) {
        val sc = scene
        if (sc != null && bitmap!!.width == w && bitmap!!.height == h) return
        sc?.close()
        scene = ImageComposeScene(w, h, Density(h / 1080f)) { HudContent(ui.value, actions) }
        bitmap = Bitmap().apply { allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
        native = Memory(w.toLong() * h * 4)
    }

    private fun render(sc: ImageComposeScene, w: Int, h: Int) {
        val bmp = bitmap!!
        sc.render(System.nanoTime()).readPixels(bmp)
        val bytes = bmp.readPixels() ?: return
        native!!.write(0, bytes, 0, bytes.size)
        mpv.addOverlay(0, Pointer.nativeValue(native), w, h, w, h)
        overlayShown = true
    }

    override fun close() {
        running = false
        scope.cancel()
        thread.join(1_000)
        runCatching { mpv.command("overlay-remove", "0") }
        scene?.close()
    }
}
