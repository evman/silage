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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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
    /** Live optional output status, refreshed with Playback Info. */
    private val liveInfoRows: () -> List<Pair<String, String>> = { emptyList() },
    /** Find / download / AI subtitles; null hides them. */
    private val subtitleTools: SubtitleTools? = null,
    /** The runtime to show instead of mpv's duration (a converted stream's grows as it's produced). */
    private val knownDuration: Double? = null,
) : AutoCloseable {
    private val ui = mutableStateOf(HudUi(meta = meta, hasSubtitleTools = subtitleTools != null))
    private val visibility = HudVisibility(hideAfterMs = 3_000)
    private val inbox = ConcurrentLinkedQueue<() -> Unit>()
    private val nativeMouseMoved = AtomicBoolean(false)
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
        override fun findSubtitles() {
            val tools = subtitleTools ?: return
            nav = nav.copy(menu = HudMenu.FindSubtitles, menuIndex = 0, menuSize = 0)
            setFinder { it.copy(status = "Searching for ${tools.searchLabel} subtitles…", busy = true, hits = emptyList()) }
            scope.launch {
                val result = tools.search()
                inbox += {
                    result.onSuccess { hits ->
                        setFinder { it.copy(busy = false, hits = hits, status = if (hits.isEmpty()) "No ${tools.searchLabel} subtitles found for this file." else null) }
                    }.onFailure { e -> setFinder { it.copy(busy = false, status = "Couldn't search: ${e.message ?: "unknown error"}") } }
                    if (nav.menu == HudMenu.FindSubtitles) nav = nav.copy(menuIndex = 0, menuSize = ui.value.finder.hits.size)
                }
            }
        }
        override fun pickHit(index: Int) {
            val tools = subtitleTools ?: return
            val hit = ui.value.finder.hits.getOrNull(index) ?: return
            if (ui.value.finder.busy) return
            val hits = ui.value.finder.hits
            nav = nav.copy(menu = HudMenu.FindSubtitles, menuIndex = 0, menuSize = 0)
            setFinder { it.copy(status = "Downloading ${hit.label}…", busy = true, hits = emptyList()) }
            scope.launch {
                val result = tools.download(hit)
                inbox += {
                    result.onSuccess { id ->
                        setFinder { it.copy(status = "Loading the subtitles…", busy = true) }
                        tools.use(id)
                    }.onFailure { e ->
                        setFinder { it.copy(busy = false, hits = hits, status = "Couldn't download: ${e.message ?: "unknown error"}") }
                        if (nav.menu == HudMenu.FindSubtitles) nav = nav.copy(menuSize = hits.size)
                    }
                }
            }
        }
        override fun createSubtitles() {
            if (subtitleTools == null) return
            val f = ui.value.finder
            setFinder { it.copy(status = null, busy = false) }
            nav = nav.copy(menu = HudMenu.AiSubtitles, menuIndex = 0, menuSize = if (f.aiProgress == null) f.aiOptions.size else 0)
        }
        override fun pickAi(index: Int) {
            val tools = subtitleTools ?: return
            val f = ui.value.finder
            val option = f.aiOptions.getOrNull(index) ?: return
            if (f.aiProgress != null) return
            nav = nav.copy(menu = HudMenu.AiSubtitles, menuIndex = 0, menuSize = 0)
            setFinder { it.copy(status = null, busy = true, aiProgress = aiProgressLabel(0.0, "starting")) }
            val position = mpv.state.value.positionSeconds
            scope.launch {
                val result = tools.create(option, position) { fraction, message ->
                    inbox += { setFinder { it.copy(aiProgress = aiProgressLabel(fraction, message)) } }
                }
                inbox += {
                    result.onSuccess { id ->
                        setFinder { it.copy(aiProgress = null, busy = true, status = "Subtitles ready, loading them…") }
                        tools.use(id)
                    }.onFailure { e ->
                        setFinder { it.copy(aiProgress = null, busy = false, status = "Couldn't create subtitles: ${e.message ?: "unknown error"}") }
                        if (nav.menu == HudMenu.AiSubtitles) nav = nav.copy(menuSize = ui.value.finder.aiOptions.size)
                    }
                }
            }
        }
        override fun video(index: Int, step: Int) {
            videoSession.update(VideoControls.adjust(videoSession.settings, index, step), mpv::setProperty, mpv::getProperty)
            ui.value = ui.value.copy(video = videoSession.settings, videoError = videoSession.error)
        }
    }

    private fun setFinder(update: (FinderUi) -> FinderUi) { ui.value = ui.value.copy(finder = update(ui.value.finder)) }

    /** Which rows of [menu] are selected, in display order. */
    private fun rowsOf(menu: HudMenu): List<Boolean> = when (menu) {
        HudMenu.Audio -> ui.value.tracks.audio.map { it.selected }
        HudMenu.Subtitles -> listOf(ui.value.tracks.subtitles.none { it.selected }) + ui.value.tracks.subtitles.map { it.selected } +
            subtitleExtras(subtitleTools != null, ui.value.finder).map { false }
        HudMenu.FindSubtitles -> ui.value.finder.hits.map { false }
        HudMenu.AiSubtitles -> if (ui.value.finder.aiProgress == null) ui.value.finder.aiOptions.map { false } else emptyList()
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
            HudMenu.Subtitles -> {
                val tracks = ui.value.tracks.subtitles
                when {
                    index == 0 -> actions.subtitle(null)
                    index - 1 < tracks.size -> actions.subtitle(tracks[index - 1].id)
                    else -> when (subtitleExtras(subtitleTools != null, ui.value.finder).getOrNull(index - 1 - tracks.size)) {
                        SubtitleExtra.Find -> actions.findSubtitles()
                        SubtitleExtra.Ai -> actions.createSubtitles()
                        null -> Unit
                    }
                }
            }
            HudMenu.FindSubtitles -> actions.pickHit(index)
            HudMenu.AiSubtitles -> actions.pickAi(index)
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
        // script-binding forwards down/up separately, including while dragging.
        mpv.command("keybind", "MBTN_LEFT", "script-binding silage-pointer")
        HudKey.MPV_KEYS.forEach { mpv.command("keybind", it, "script-message silage-key $it") }
        subtitleTools?.let { tools ->
            scope.launch {
                val options = runCatching { tools.aiOptions() }.getOrDefault(emptyList())
                inbox += { setFinder { it.copy(aiOptions = options) } }
            }
        }
        scope.launch {
            mpv.events.filterIsInstance<PlayerEvent.ClientMessage>().collect { m ->
                when (m.args.firstOrNull()) {
                    // High polling-rate mice must not queue property reads for every sample.
                    "silage-mouse" -> nativeMouseMoved.set(true)
                    "key-binding" -> if (m.args.getOrNull(1) == "silage-pointer") {
                        mousePos()?.let { p ->
                            when (m.args.getOrNull(2)?.firstOrNull()) {
                                'd' -> pointer(PointerEventType.Press, p.x, p.y)
                                'u' -> pointer(PointerEventType.Release, p.x, p.y)
                                'p' -> click(p.x, p.y)
                            }
                        }
                    }
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

    /**
     * A double-click on the picture toggles fullscreen, as in Windows players;
     * only in the middle band, away from the back button and the player bar.
     */
    fun videoDoubleClick(fy: Float) {
        inbox += { if (nav.menu == HudMenu.None && fy in DoubleClickBand) actions.fullscreen() }
    }

    /** A keyboard media key (Play/Pause, Next, Previous, Stop): shows the bar and acts. */
    fun mediaKey(key: MediaKey) {
        inbox += {
            visibility.activity()
            nav = nav.copy(visible = true)
            when (key) {
                MediaKey.PlayPause -> actions.togglePause()
                MediaKey.Stop -> actions.exit()
                MediaKey.Next -> if (onNextEpisode != null && hasNextEpisode()) actions.nextEpisode() else actions.seekBy(30.0)
                MediaKey.Previous -> actions.seekBy(-10.0)
            }
        }
    }

    /** A press, release or move as fractions (0..1) of the video surface, so the seek bar can be dragged. */
    fun pointerEvent(fx: Float, fy: Float, type: PointerEventType, buttonHeld: Boolean = type == PointerEventType.Press) {
        inbox += {
            val b = bitmap
            if (b != null) {
                // A release that never arrived (the button was let go elsewhere) must not leave a drag running.
                if (type == PointerEventType.Move && !buttonHeld && primaryDown) pointerNow(PointerEventType.Release, fx * b.width, fy * b.height)
                pointerNow(type, fx * b.width, fy * b.height)
            }
        }
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

    /** The primary button is held (a press reached the scene and no release yet): moves then drag. */
    private var primaryDown = false
    /** A press only revealed the hidden HUD, so its release is not passed on either. */
    private var swallowRelease = false

    private fun pointerNow(type: PointerEventType, x: Float, y: Float) {
        visibility.activity()
        keyboardFocus = false
        if (type == PointerEventType.Release && swallowRelease) { swallowRelease = false; return }
        if (!nav.visible) {
            nav = nav.copy(visible = true)
            if (type == PointerEventType.Press) { swallowRelease = true; return } // the first click only reveals the HUD
        }
        when (type) {
            PointerEventType.Press -> primaryDown = true
            PointerEventType.Release -> primaryDown = false
        }
        scene?.sendPointerEvent(
            eventType = type,
            position = Offset(x, y),
            buttons = if (primaryDown) PointerButtons(isPrimaryPressed = true) else PointerButtons(),
            button = if (type == PointerEventType.Move) null else PointerButton.Primary,
        )
    }

    private fun mousePos(): Offset? {
        val pos = runCatching { Json.parseToJsonElement(mpv.getProperty("mouse-pos") ?: return null).jsonObject }.getOrNull() ?: return null
        val x = pos["x"]?.jsonPrimitive?.floatOrNull ?: return null
        val y = pos["y"]?.jsonPrimitive?.floatOrNull ?: return null
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
        if (nativeMouseMoved.getAndSet(false)) mousePos()?.let { pointerNow(PointerEventType.Move, it.x, it.y) }

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
            infoRows + InfoRows.build(mpv::getProperty) + liveInfoRows()
        } else {
            ui.value.info
        }
        // Quantized so a static HUD doesn't redraw every frame.
        val next = ui.value.copy(
            position = if (visible) (s.positionSeconds * 4).toLong() / 4.0 else ui.value.position,
            duration = knownDuration ?: s.durationSeconds,
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
