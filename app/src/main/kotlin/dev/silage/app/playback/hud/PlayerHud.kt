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
import dev.silage.app.playback.PlaybackFullscreen
import dev.silage.app.playback.VideoSession
import dev.silage.app.playback.VideoControls
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
/**
 * The size the HUD is drawn at for an OSD of [w]×[h]: at most 1080 lines,
 * scaled up by mpv. mpv copies each overlay on the thread that feeds audio,
 * and full-4K redraws starved it (PipeWire underruns, crackling audio).
 */
fun hudSceneSize(w: Int, h: Int): Pair<Int, Int> {
    val sh = minOf(h, 1080)
    return (w.toLong() * sh / h).toInt() to sh
}

class PlayerHud(private val mpv: MpvPlayer, meta: PlaybackMeta,
    private val audioRows: ((List<Track>) -> List<Track>)? = null,
    private val selectSourceAudio: ((Int) -> Unit)? = null,
    private val fullscreenWindow: PlaybackFullscreen? = null,
    private val videoSession: VideoSession = VideoSession(),
    /** Jump past the intro marker by itself, once per playback. */
    private val autoSkipIntro: Boolean = false,
    /** Whether a next episode is known yet; while it is, the credits button reads "Next Episode". */
    private val hasNextEpisode: () -> Boolean = { false },
    /** Ends this episode and moves on to the next. */
    private val onNextEpisode: (() -> Unit)? = null,
    /** Rows shown first in Playback Info (which copy is playing). */
    private val infoRows: List<Pair<String, String>> = emptyList(),
) : AutoCloseable {
    private val ui = mutableStateOf(HudUi(meta = meta))
    private val visibility = HudVisibility(hideAfterMs = 3_000)
    private val inbox = ConcurrentLinkedQueue<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = true

    // HUD-thread state.
    private var nav = HudNav()
    private var keyboardFocus = false

    private val actions = object : HudActions {
        override fun togglePause() { mpv.command("cycle", "pause") }
        override fun seekBy(seconds: Double) { mpv.command("seek", seconds.toString(), "relative+exact") }
        override fun seekTo(seconds: Double) { mpv.command(*HudCommands.seekTo(seconds)) }
        override fun openMenu(menu: HudMenu) {
            if (nav.menu == menu) {
                nav = nav.copy(menu = HudMenu.None)
                return
            }
            if (menu == HudMenu.Audio || menu == HudMenu.Subtitles) refreshTracks()
            val rows = rowsOf(menu)
            nav = nav.copy(menu = menu, menuIndex = rows.indexOfFirst { it }.coerceAtLeast(0), menuSize = rows.size)
        }
        override fun audio(id: Int) {
            if (selectSourceAudio != null) selectSourceAudio.invoke(id) else mpv.command(*HudCommands.audio(id))
            refreshTracks(); nav = nav.copy(menu = HudMenu.None)
        }
        override fun subtitle(id: Int?) { mpv.command(*HudCommands.subtitle(id)); refreshTracks(); nav = nav.copy(menu = HudMenu.None) }
        override fun speed(value: Double) {
            mpv.command(*HudCommands.speed(value))
            ui.value = ui.value.copy(speed = value)
            nav = nav.copy(menu = HudMenu.None)
        }
        override fun exit() { mpv.command("stop") }
        override fun fullscreen() {
            if (fullscreenWindow != null) fullscreenWindow.toggle() else mpv.command("cycle", "fullscreen")
        }
        override fun chapter(index: Int) {
            ui.value.chapters.getOrNull(index)?.let { seekTo(it.start) }
            nav = nav.copy(menu = HudMenu.None)
        }
        override fun nextEpisode() { onNextEpisode?.invoke() }
        override fun video(index: Int, step: Int) {
            videoSession.update(VideoControls.adjust(videoSession.settings, index, step), mpv::setProperty, mpv::getProperty)
            ui.value = ui.value.copy(video = videoSession.settings, videoError = videoSession.error)
        }
    }

    /** Which rows of [menu] are selected, in display order. */
    private fun rowsOf(menu: HudMenu): List<Boolean> = when (menu) {
        HudMenu.Audio -> ui.value.tracks.audio.map { it.selected }
        HudMenu.Subtitles -> listOf(ui.value.tracks.subtitles.none { it.selected }) + ui.value.tracks.subtitles.map { it.selected }
        HudMenu.Speed -> SPEEDS.map { it == ui.value.speed }
        HudMenu.Video -> List(VideoControls.RowCount) { false }
        HudMenu.Chapters -> Chapter.indexAt(ui.value.chapters, mpv.state.value.positionSeconds).let { current ->
            ui.value.chapters.indices.map { it == current }
        }
        else -> emptyList()
    }

    private fun selectRow(menu: HudMenu, index: Int) {
        when (menu) {
            HudMenu.Audio -> ui.value.tracks.audio.getOrNull(index)?.let { actions.audio(it.id) }
            HudMenu.Subtitles -> if (index == 0) actions.subtitle(null) else ui.value.tracks.subtitles.getOrNull(index - 1)?.let { actions.subtitle(it.id) }
            HudMenu.Speed -> SPEEDS.getOrNull(index)?.let(actions::speed)
            HudMenu.Chapters -> actions.chapter(index)
            else -> Unit
        }
    }

    private val thread = Thread(::loop, "silage-hud").apply { isDaemon = true; start() }

    init {
        // In mpv's own window (HDR) mpv has the keyboard and mouse; these
        // bindings forward them. Embedded, input arrives through [key]/[pointer].
        mpv.command("keybind", "MOUSE_MOVE", "script-message silage-mouse")
        mpv.command("keybind", "MBTN_LEFT", "script-message silage-click")
        HudKey.MPV_KEYS.forEach { mpv.command("keybind", it, "script-message silage-key $it") }
        scope.launch {
            mpv.events.filterIsInstance<PlayerEvent.ClientMessage>().collect { m ->
                when (m.args.firstOrNull()) {
                    "silage-mouse" -> mousePos()?.let { pointer(PointerEventType.Move, it.x, it.y) }
                    "silage-click" -> mousePos()?.let { click(it.x, it.y) }
                    "silage-key" -> m.args.getOrNull(1)?.let(HudKey::ofMpv)?.let(::key)
                }
            }
        }
    }

    /** Opens (or toggles) a menu, as its HUD button would. */
    fun open(menu: HudMenu) { inbox += { visibility.activity(); nav = nav.copy(visible = true); actions.openMenu(menu) } }

    /** A key from any source (AWT or mpv). */
    fun key(key: HudKey) {
        inbox += {
            visibility.activity()
            keyboardFocus = true
            nav = nav.copy(fullscreen = isFullscreen())
            val (next, effect) = nav.on(key)
            nav = next
            when (effect) {
                HudEffect.None -> Unit
                HudEffect.Exit -> actions.exit()
                HudEffect.TogglePause -> actions.togglePause()
                HudEffect.ToggleFullscreen -> actions.fullscreen()
                HudEffect.LeaveFullscreen -> if (fullscreenWindow != null) fullscreenWindow.leave() else mpv.setProperty("fullscreen", "no")
                is HudEffect.AdjustVideo -> actions.video(effect.index, effect.step)
                is HudEffect.SeekBy -> actions.seekBy(effect.seconds)
                is HudEffect.SelectRow -> selectRow(effect.menu, effect.index)
                is HudEffect.Activate -> when (effect.control) {
                    HudFocus.Skip -> ui.value.skip?.let { if (it.kind == SkipPrompt.Kind.NextEpisode) actions.nextEpisode() else actions.seekTo(it.target) }
                    HudFocus.Seek, HudFocus.PlayPause -> actions.togglePause()
                    HudFocus.Back10 -> actions.seekBy(-10.0)
                    HudFocus.Fwd10 -> actions.seekBy(10.0)
                    HudFocus.Audio -> actions.openMenu(HudMenu.Audio)
                    HudFocus.Subtitles -> actions.openMenu(HudMenu.Subtitles)
                    HudFocus.Speed -> actions.openMenu(HudMenu.Speed)
                    HudFocus.Chapters -> actions.openMenu(HudMenu.Chapters)
                    HudFocus.Info -> actions.openMenu(HudMenu.Info)
                    HudFocus.Video -> actions.openMenu(HudMenu.Video)
                    HudFocus.Fullscreen -> actions.fullscreen()
                }
            }
        }
    }

    /** Pointer movement in OSD pixels (the HUD's own coordinates). */
    fun pointer(type: PointerEventType, x: Float, y: Float) {
        inbox += { pointerNow(type, x, y) }
    }

    /** Pointer input as fractions (0..1) of the video surface, from the Silage window. */
    fun pointerAt(fx: Float, fy: Float, press: Boolean) {
        inbox += {
            val b = bitmap
            if (b != null) {
                val x = fx * b.width
                val y = fy * b.height
                if (press) { pointerNow(PointerEventType.Press, x, y); pointerNow(PointerEventType.Release, x, y) }
                else pointerNow(PointerEventType.Move, x, y)
            }
        }
    }

    fun click(x: Float, y: Float) {
        inbox += { pointerNow(PointerEventType.Press, x, y); pointerNow(PointerEventType.Release, x, y) }
    }

    private var host: HudSceneHost? = null
    private val scene: ImageComposeScene? get() = host?.scene
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var bitmap: Bitmap? = null
    private var native: Memory? = null
    private var overlayShown = false
    private var lastInfoAt = 0L
    private var chaptersLoaded = false
    private var introAutoSkipped = false

    private fun pointerNow(type: PointerEventType, x: Float, y: Float) {
        visibility.activity()
        keyboardFocus = false
        if (!nav.visible) {
            nav = nav.copy(visible = true)
            if (type != PointerEventType.Move) return // the first click only reveals the HUD
        }
        scene?.sendPointerEvent(
            eventType = type,
            position = Offset(x, y),
            buttons = if (type == PointerEventType.Press) PointerButtons(isPrimaryPressed = true) else PointerButtons(),
            button = if (type == PointerEventType.Move) null else PointerButton.Primary,
        )
    }

    private fun mousePos(): Offset? {
        val x = mpv.getProperty("mouse-pos/x")?.toFloatOrNull() ?: return null
        val y = mpv.getProperty("mouse-pos/y")?.toFloatOrNull() ?: return null
        val b = bitmap ?: return Offset(x, y)
        return if (osdW > 0 && osdH > 0) Offset(x * b.width / osdW, y * b.height / osdH) else Offset(x, y)
    }

    private fun refreshTracks() {
        val mounted = Tracks.parse(mpv.getProperty("track-list") ?: "[]")
        ui.value = ui.value.copy(tracks = mounted.copy(audio = audioRows?.invoke(mounted.audio) ?: mounted.audio))
    }

    private fun isFullscreen() = fullscreenWindow?.active ?: (mpv.getProperty("fullscreen") == "yes")

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
        // Chapters are known once the file is loaded (it has a duration).
        if (!chaptersLoaded && s.durationSeconds > 0) {
            chaptersLoaded = true
            val chapters = Chapter.parse(mpv.getProperty("chapter-list"))
            ui.value = ui.value.copy(chapters = chapters)
            nav = nav.copy(hasChapters = chapters.isNotEmpty())
        }
        val skip = SkipPrompt.at(s.positionSeconds, ui.value.meta.intro, ui.value.meta.credits,
            hasNextEpisode = onNextEpisode != null && hasNextEpisode())
        if (autoSkipIntro && !introAutoSkipped && skip?.kind == SkipPrompt.Kind.Intro) {
            introAutoSkipped = true
            actions.seekTo(skip.target)
        }
        if (nav.visible && !visibility.visible(now, s.paused, nav.menu != HudMenu.None)) nav = nav.copy(visible = false, focus = HudFocus.PlayPause)
        nav = nav.copy(hasSkip = skip != null, focus = if (skip == null && nav.focus == HudFocus.Skip) HudFocus.Seek else nav.focus)
        val visible = nav.visible
        val info = if (nav.menu == HudMenu.Info && now - lastInfoAt > 1_000) {
            lastInfoAt = now
            infoRows + InfoRows.build(mpv::getProperty)
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
            menu = nav.menu,
            menuIndex = nav.menuIndex,
            focus = if (keyboardFocus && visible) nav.focus else null,
            info = info,
            skip = skip,
            video = videoSession.settings,
            videoError = videoSession.error,
            fullscreen = isFullscreen(),
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

    // OSD size (what mpv displays) vs. scene size (what we draw and copy).
    private var osdW = 0
    private var osdH = 0

    private fun ensureScene(w: Int, h: Int) {
        osdW = w; osdH = h
        val (sw, sh) = hudSceneSize(w, h)
        if (host != null && bitmap!!.width == sw && bitmap!!.height == sh) return
        host?.close()
        host = HudSceneHost(sw, sh, Density(sh / 1080f)) { HudContent(ui.value, actions) }
        bitmap = Bitmap().apply { allocPixels(ImageInfo(sw, sh, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
        native = Memory(sw.toLong() * sh * 4)
    }

    private fun render(sc: ImageComposeScene, w: Int, h: Int) {
        val bmp = bitmap!!
        sc.render(System.nanoTime()).readPixels(bmp)
        val bytes = bmp.readPixels() ?: return
        native!!.write(0, bytes, 0, bytes.size)
        mpv.addOverlay(0, Pointer.nativeValue(native), bmp.width, bmp.height, w, h)
        overlayShown = true
    }

    /** Idempotent: the player is closed more than once on the way out. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running = false
        scope.cancel()
        thread.join(1_000)
        runCatching { mpv.command("overlay-remove", "0") }
        host?.close()
    }
}
