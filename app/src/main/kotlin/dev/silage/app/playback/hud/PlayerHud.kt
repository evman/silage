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
import dev.silage.app.playback.SeekThumbnail
import dev.silage.app.playback.SeekThumbnails
import dev.silage.app.playback.thumbnailSlot
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
    /** Told after every update, so a deadlocked mpv (which blocks this loop) is noticed. */
    private val watchdog: dev.silage.app.playback.PlaybackWatchdog? = null,
    private val audioRows: ((List<Track>) -> List<Track>)? = null,
    private val selectSourceAudio: ((Int) -> Unit)? = null,
    private val fullscreenWindow: PlaybackFullscreen? = null,
    private val videoSession: VideoSession = VideoSession(),
    /** Markers to jump past by itself, each once per playback. */
    private val autoSkipDefaults: AutoSkip = AutoSkip(),
    /** Whether a next episode is known yet; while it is, the credits button reads "Next Episode". */
    private val hasNextEpisode: () -> Boolean = { false },
    /** Ends this episode and moves on to the next. */
    private val onNextEpisode: (() -> Unit)? = null,
    /** Quits Silage (Ctrl+Q). */
    private val onQuit: () -> Unit = {},
    /** D: Dolby Vision off / on for comparison; returns what to say. Null where Dolby Vision isn't in use. */
    private val onToggleDolbyVision: (() -> String)? = null,
    /** Rows shown first in Playback Info (which copy is playing). */
    private val infoRows: List<Pair<String, String>> = emptyList(),
    /** Live optional output status, refreshed with Playback Info. */
    private val liveInfoRows: () -> List<Pair<String, String>> = { emptyList() },
    /** Find / download / AI subtitles; null hides them. */
    private val subtitleTools: SubtitleTools? = null,
    /** The runtime to show instead of mpv's duration (a converted stream's grows as it's produced). */
    private val knownDuration: Double? = null,
    /** The Quality menu's rows; empty hides it. */
    qualities: List<QualityRow> = emptyList(),
    /** Restarts playback at the picked quality. */
    private val selectQuality: ((String) -> Unit)? = null,
    /** Told the volume (0–130) and mute after a shortcut changes them, so the next video starts the same. */
    private val onVolumeChange: (volume: Int, muted: Boolean) -> Unit = { _, _ -> },
    /** Makes the seek bar's previews, given where to send each frame; null shows only the time. */
    thumbnails: ((onFrame: (SeekThumbnail) -> Unit) -> SeekThumbnails)? = null,
    /** In a Watch Party, where play, pause and seeks go instead of straight to mpv. */
    private val party: PartyTransport? = null,
    /** How subtitles look now, and where a change is kept. */
    subtitleLook: dev.silage.app.playback.SubtitleStyle = dev.silage.app.playback.SubtitleStyle(),
    private val onSubtitleLook: (dev.silage.app.playback.SubtitleStyle) -> Unit = {},
    /** What this profile chose for this show; null when it isn't one. */
    private val showMemory: dev.silage.app.playback.ShowMemory? = null,
    /** What to do about repeated buffering; null leaves it alone (a Watch Party, a local file). */
    private val bufferingPlan: BufferingPlan? = null,
) : AutoCloseable {
    private val bufferingGuard = BufferingGuard().also { it.started(System.currentTimeMillis()) }
    private var offerAccept: (() -> Unit)? = null
    private var offerUntil = 0L

    private fun showOffer(offer: QualityOffer, onAccept: () -> Unit) {
        offerAccept = onAccept
        offerUntil = System.currentTimeMillis() + OfferShownMs
        ui.value = ui.value.copy(qualityOffer = offer)
    }

    private fun clearOffer() { offerAccept = null; ui.value = ui.value.copy(qualityOffer = null) }

    /** Looks at the player once a frame: enough stops, and it moves to the 1080p copy or asks. */
    private fun watchBuffering(s: dev.silage.player.PlayerState, now: Long) {
        val plan = bufferingPlan
        if (ui.value.qualityOffer != null && now > offerUntil) clearOffer()
        if (plan == null || !bufferingGuard.update(s.buffering, !s.paused, now)) return
        if (plan.automatic) plan.switch()
        else showOffer(QualityOffer("This 4K video keeps stopping to buffer.", "Play in 1080p", "Stay in 4K"), plan.switch)
    }

    private val clock = HudClock()
    private val autoSkip: AutoSkip get() = dev.silage.app.playback.autoSkipFor(autoSkipDefaults, ui.value.showAutoSkip)
    private val previews: SeekThumbnails? = thumbnails?.invoke { frame -> inbox += { ui.value = ui.value.copy(thumbnail = frame) } }
    /** The preview slot asked for last, so moving within a slot asks nothing more. */
    private var previewSlot: Double? = null
    /** The newest pointer move not yet passed on: a fast mouse sends many per frame and only the last matters. */
    private val pendingMove = java.util.concurrent.atomic.AtomicReference<Triple<Float, Float, Boolean>?>(null)
    // Reading these from mpv takes its lock, so the 60 Hz loop only asks now and then.
    private var nextOsdCheck = 0L
    private var nextBufferCheck = 0L
    private var cachedW = 0
    private var cachedH = 0
    private var cachedFullscreen = false

    private val ui = mutableStateOf(HudUi(meta = meta, hasSubtitleTools = subtitleTools != null, qualities = if (selectQuality != null) qualities else emptyList(),
        subtitleLook = subtitleLook, hasShowMemory = showMemory != null, showAutoSkip = showMemory?.current?.autoSkip))
    private val visibility = HudVisibility(hideAfterMs = 3_000)
    private val inbox = ConcurrentLinkedQueue<() -> Unit>()
    private val nativeMouseMoved = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = true

    /** Whether the HUD is on screen (the pointer over the video hides with it). */
    @Volatile var hudVisible: Boolean = true
        private set

    // HUD-thread state.
    private var nav = HudNav(hasSleep = party == null)
    private var keyboardFocus = false

    private val actions = object : HudActions {
        override fun answerQualityOffer(accept: Boolean) {
            val action = offerAccept
            clearOffer()
            if (accept) action?.invoke()
        }
        override fun uploadSubtitle() {
            val tools = subtitleTools ?: return
            nav = nav.copy(menu = HudMenu.None)
            scope.launch {
                val file = dev.silage.app.playback.chooseSubtitleFile() ?: return@launch
                inbox += {
                    nav = nav.copy(menu = HudMenu.FindSubtitles, menuIndex = 0, menuSize = 0)
                    setFinder { it.copy(status = "Uploading ${file.fileName}…", busy = true, hits = emptyList()) }
                }
                val result = tools.upload(file)
                inbox += {
                    result.onSuccess { id ->
                        setFinder { it.copy(status = "Loading the subtitles…", busy = true) }
                        tools.use(id)
                    }.onFailure { e -> setFinder { it.copy(busy = false, status = "Couldn't upload: ${e.message ?: "unknown error"}") } }
                }
            }
        }
        override fun togglePause() {
            if (party != null) party.playPause(!mpv.state.value.paused)?.let(::toast) else mpv.command("cycle", "pause")
        }
        override fun seekBy(seconds: Double) {
            bufferingGuard.seeked(System.currentTimeMillis())
            if (party != null) party.seekTo((mpv.state.value.positionSeconds + seconds).coerceAtLeast(0.0))?.let(::toast)
            else mpv.command("seek", seconds.toString(), "relative+exact")
        }
        override fun seekTo(seconds: Double) {
            bufferingGuard.seeked(System.currentTimeMillis())
            if (party != null) party.seekTo(seconds)?.let(::toast) else mpv.command(*HudCommands.seekTo(seconds))
        }
        override fun previewAt(seconds: Double?) {
            val p = previews ?: return
            val slot = seconds?.let { thumbnailSlot(it, ui.value.duration) }
            if (slot == previewSlot) return
            previewSlot = slot
            if (slot != null) p.request(slot)
        }
        override fun openMenu(menu: HudMenu) {
            if (nav.menu == menu) {
                nav = nav.copy(menu = HudMenu.None)
                return
            }
            if (menu == HudMenu.Audio || menu == HudMenu.Subtitles || menu == HudMenu.Playback) refreshTracks()
            val rows = rowsOf(menu)
            nav = nav.copy(menu = menu, menuIndex = rows.indexOfFirst { it }.coerceAtLeast(0), menuSize = rows.size)
        }
        override fun audio(id: Int) {
            showMemory?.audio(ui.value.tracks.audio.firstOrNull { it.id == id }?.language)
            if (selectSourceAudio != null) selectSourceAudio.invoke(id) else mpv.command(*HudCommands.audio(id))
            refreshTracks(); nav = nav.copy(menu = HudMenu.None)
        }
        override fun subtitle(id: Int?) { showMemory?.subtitle(ui.value.tracks.subtitles.firstOrNull { it.id == id }?.language, off = id == null); mpv.command(*HudCommands.subtitle(id)); refreshTracks(); nav = nav.copy(menu = HudMenu.None) }
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
        override fun copyInfo() = this@PlayerHud.copyInfo()
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
        override fun sync(index: Int, step: Int) {
            val u = ui.value
            val (audio, subtitles) = when (index) {
                SyncControls.ResetIndex -> 0.0 to 0.0
                SyncControls.AudioIndex -> SyncControls.adjust(u.audioDelay, step) to u.subtitleDelay
                else -> u.audioDelay to SyncControls.adjust(u.subtitleDelay, step)
            }
            mpv.setProperty("audio-delay", "%.1f".format(java.util.Locale.ROOT, audio))
            mpv.setProperty("sub-delay", "%.1f".format(java.util.Locale.ROOT, subtitles))
            ui.value = u.copy(audioDelay = audio, subtitleDelay = subtitles)
        }
        override fun sleep(choice: SleepChoice) {
            sleepAt = choice.minutes?.let { System.currentTimeMillis() + it * 60_000L }
            ui.value = ui.value.copy(sleep = choice, sleepDetail = sleepDetail(choice))
            nav = nav.copy(menu = HudMenu.None)
            if (choice != SleepChoice.Off) toast("Sleep timer: ${choice.label.replaceFirstChar { it.lowercase() }}")
        }
        override fun subtitleLook(index: Int, step: Int) {
            val next = dev.silage.app.playback.SubtitleLookRows.stepped(ui.value.subtitleLook, index, step)
            next.mpvOptions().forEach { (name, value) -> mpv.setProperty(name, value) }
            ui.value = ui.value.copy(subtitleLook = next)
            onSubtitleLook(next)
        }
        override fun showAutoSkip(value: Boolean?) {
            showMemory?.autoSkip(value)
            ui.value = ui.value.copy(showAutoSkip = value)
            nav = nav.copy(menu = HudMenu.None)
        }
        override fun quality(id: String) {
            nav = nav.copy(menu = HudMenu.None)
            selectQuality?.invoke(id)
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
        HudMenu.Playback -> playbackEntries(ui.value).map { false }
        HudMenu.Picture -> pictureEntries(ui.value).map { false }
        HudMenu.Subtitles -> listOf(ui.value.tracks.subtitles.none { it.selected }) + ui.value.tracks.subtitles.map { it.selected } +
            subtitleExtras(subtitleTools != null, ui.value.finder).map { false }
        HudMenu.FindSubtitles -> ui.value.finder.hits.map { false }
        HudMenu.AiSubtitles -> if (ui.value.finder.aiProgress == null) ui.value.finder.aiOptions.map { false } else emptyList()
        HudMenu.Speed -> SPEEDS.map { it == ui.value.speed }
        HudMenu.Quality -> ui.value.qualities.map { it.selected }
        HudMenu.Sync -> List(SyncControls.RowCount) { false }
        HudMenu.SubtitleLook -> List(dev.silage.app.playback.SubtitleLookRows.Count) { false }
        HudMenu.ThisShow -> ShowAutoSkipChoices.map { it.first == ui.value.showAutoSkip }
        HudMenu.Sleep -> SleepChoice.entries.map { it == ui.value.sleep }
        HudMenu.Video -> List(VideoControls.RowCount) { false }
        HudMenu.Chapters -> Chapter.indexAt(ui.value.chapters, mpv.state.value.positionSeconds).let { current ->
            ui.value.chapters.indices.map { it == current }
        }
        else -> emptyList()
    }

    private fun selectRow(menu: HudMenu, index: Int) {
        when (menu) {
            HudMenu.Audio -> ui.value.tracks.audio.getOrNull(index)?.let { actions.audio(it.id) }
            HudMenu.Playback -> playbackEntries(ui.value).getOrNull(index)?.let { actions.openMenu(it.first) }
            HudMenu.Picture -> pictureEntries(ui.value).getOrNull(index)?.let { actions.openMenu(it.first) }
            HudMenu.Subtitles -> {
                val tracks = ui.value.tracks.subtitles
                when {
                    index == 0 -> actions.subtitle(null)
                    index - 1 < tracks.size -> actions.subtitle(tracks[index - 1].id)
                    else -> when (subtitleExtras(subtitleTools != null, ui.value.finder).getOrNull(index - 1 - tracks.size)) {
                        SubtitleExtra.Find -> actions.findSubtitles()
                        SubtitleExtra.Upload -> actions.uploadSubtitle()
                        SubtitleExtra.Ai -> actions.createSubtitles()
                        null -> Unit
                    }
                }
            }
            HudMenu.FindSubtitles -> actions.pickHit(index)
            HudMenu.AiSubtitles -> actions.pickAi(index)
            HudMenu.Speed -> SPEEDS.getOrNull(index)?.let(actions::speed)
            HudMenu.Quality -> ui.value.qualities.getOrNull(index)?.let { actions.quality(it.id) }
            HudMenu.Sleep -> SleepChoice.entries.getOrNull(index)?.let { actions.sleep(it) }
            HudMenu.Chapters -> actions.chapter(index)
            HudMenu.ThisShow -> ShowAutoSkipChoices.getOrNull(index)?.let { actions.showAutoSkip(it.first) }
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

    /** A short message over the video, shown even with the HUD hidden. */
    fun notice(message: String) { inbox += { toast(message) } }

    /** The Watch Party line under the title. */
    fun partyLine(line: String?) { inbox += { ui.value = ui.value.copy(partyLine = line) } }

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
                is HudEffect.OpenMenu -> actions.openMenu(effect.menu)
                HudEffect.Exit -> actions.exit()
                HudEffect.TogglePause -> actions.togglePause()
                HudEffect.ToggleFullscreen -> actions.fullscreen()
                is HudEffect.AdjustVideo -> actions.video(effect.index, effect.step)
                is HudEffect.SeekBy -> actions.seekBy(effect.seconds)
                is HudEffect.SelectRow -> selectRow(effect.menu, effect.index)
                is HudEffect.AdjustSync -> actions.sync(effect.index, effect.step)
                is HudEffect.AdjustSubtitleLook -> actions.subtitleLook(effect.index, effect.step)
                is HudEffect.Volume -> changeVolume(effect.step)
                HudEffect.Mute -> toggleMute()
                HudEffect.CycleSubtitles -> cycleSubtitles()
                HudEffect.CycleAudio -> cycleAudio()
                is HudEffect.Speed -> if (party != null) toast("A Watch Party plays at normal speed") else nextSpeed(SPEEDS, ui.value.speed, effect.step).let { s ->
                    actions.speed(s); toast(if (s == 1.0) "Normal speed" else "Speed ${s}×")
                }
                HudEffect.NextEpisode -> if (onNextEpisode != null && hasNextEpisode()) actions.nextEpisode() else toast("No next episode")
                HudEffect.CopyInfo -> copyInfo()
                HudEffect.Quit -> onQuit()
                HudEffect.ToggleDolbyVision -> toast(onToggleDolbyVision?.invoke() ?: "Dolby Vision isn't in use", 3_000)
                is HudEffect.Activate -> when (effect.control) {
                    HudFocus.Skip -> ui.value.skip?.let { if (it.kind == SkipPrompt.Kind.NextEpisode) actions.nextEpisode() else actions.seekTo(it.target) }
                        ?: actions.answerQualityOffer(true)
                    HudFocus.Seek, HudFocus.PlayPause -> actions.togglePause()
                    HudFocus.Back10 -> actions.seekBy(-10.0)
                    HudFocus.Fwd10 -> actions.seekBy(10.0)
                    HudFocus.Audio -> actions.openMenu(HudMenu.Audio)
                    HudFocus.Playback -> actions.openMenu(HudMenu.Playback)
                    HudFocus.Chapters -> actions.openMenu(HudMenu.Chapters)
                    HudFocus.Info -> actions.openMenu(HudMenu.Info)
                    HudFocus.Picture -> actions.openMenu(pictureMenu(ui.value))
                    HudFocus.Sleep -> actions.openMenu(HudMenu.Sleep)
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
        if (type == PointerEventType.Move) { pendingMove.set(Triple(fx, fy, buttonHeld)); return }
        inbox += {
            flushPointerMove()
            bitmap?.let { b -> pointerNow(type, fx * b.width, fy * b.height) }
        }
    }

    private fun flushPointerMove() {
        val (fx, fy, held) = pendingMove.getAndSet(null) ?: return
        val b = bitmap ?: return
        // A release that never arrived (the button was let go elsewhere) must not leave a drag running.
        if (!held && primaryDown) pointerNow(PointerEventType.Release, fx * b.width, fy * b.height)
        pointerNow(PointerEventType.Move, fx * b.width, fy * b.height)
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
    private val autoSkipped = mutableSetOf<SkipPrompt.Kind>()

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

    // Shortcut feedback and the sleep timer (HUD thread).
    private var toastUntil = 0L
    private var sleepAt: Long? = null

    /** Copy details: Playback Info as plain text on the system clipboard (Linux and Windows alike). */
    private fun copyInfo() {
        val text = playbackInfoText(ui.value.info, "${dev.silage.app.BuildInfo.NAME} ${dev.silage.app.BuildInfo.VERSION}",
            System.getProperty("os.name"))
        java.awt.EventQueue.invokeLater {
            val copied = runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
            }.isSuccess
            toast(if (copied) "Playback info copied" else "Couldn't copy playback info")
        }
    }

    /** After playback moved to the 1080p copy: the way back, for a moment. */
    fun offerBackTo4k(onBack: () -> Unit) {
        inbox += { showOffer(QualityOffer("Playing the 1080p version because of buffering.", "Back to 4K"), onBack) }
    }

    /** Shows a short message over the video, as the keyboard shortcuts do. */
    fun notice(message: String, forMs: Long = 1_600) { inbox += { toast(message, forMs) } }

    private fun toast(message: String, forMs: Long = 1_600) {
        toastUntil = System.currentTimeMillis() + forMs
        ui.value = ui.value.copy(toast = message)
    }

    private fun sleepDetail(choice: SleepChoice): String? = when {
        choice == SleepChoice.EndOfVideo -> "pauses at the end"
        sleepAt != null -> "pauses at " + java.time.format.DateTimeFormatter.ofPattern("H:mm")
            .format(java.time.LocalTime.now().plusMinutes(choice.minutes!!.toLong()))
        else -> null
    }

    private fun volumeNow(): Int = mpv.getProperty("volume")?.toDoubleOrNull()?.let { kotlin.math.round(it).toInt() } ?: 100
    private fun mutedNow(): Boolean = mpv.getProperty("mute") == "yes"

    private fun changeVolume(step: Int) {
        if (step > 0 && mutedNow()) mpv.setProperty("mute", "no")
        val target = (volumeNow() + 5 * step).coerceIn(0, 130)
        mpv.setProperty("volume", target.toString())
        toast("Volume $target%")
        onVolumeChange(target, mutedNow())
    }

    private fun toggleMute() {
        mpv.command("cycle", "mute")
        val muted = mutedNow()
        toast(if (muted) "Muted" else "Sound on · ${volumeNow()}%")
        onVolumeChange(volumeNow(), muted)
    }

    private fun cycleSubtitles() {
        refreshTracks()
        val subs = ui.value.tracks.subtitles
        if (subs.isEmpty()) { toast("No subtitles"); return }
        val next = nextInCycle(listOf<Int?>(null) + subs.map { it.id }, subs.firstOrNull { it.selected }?.id)
        actions.subtitle(next)
        toast(subs.firstOrNull { it.id == next }?.let { "Subtitles: ${it.label}" } ?: "Subtitles off")
    }

    private fun cycleAudio() {
        refreshTracks()
        val audio = ui.value.tracks.audio
        if (audio.size < 2) { toast("Only one audio track"); return }
        val next = nextInCycle(audio.map { it.id }, audio.firstOrNull { it.selected }?.id ?: audio.first().id)
        actions.audio(next)
        toast("Audio: ${audio.first { it.id == next }.label}")
    }

    /** Pauses when the sleep timer is up: at its time, or at the end of the video (the credits, if marked). */
    private fun checkSleep(position: Double, duration: Double, paused: Boolean, now: Long) {
        val choice = ui.value.sleep
        if (choice == SleepChoice.Off || paused) return
        val due = when (choice) {
            SleepChoice.EndOfVideo -> sleepEndPoint(duration, ui.value.meta.credits?.start)?.let { position >= it } ?: false
            else -> sleepAt?.let { now >= it } ?: false
        }
        if (!due) return
        mpv.setProperty("pause", "yes")
        sleepAt = null
        ui.value = ui.value.copy(sleep = SleepChoice.Off, sleepDetail = null)
        nav = nav.copy(visible = true)
        toast("Paused by the sleep timer")
    }

    private fun refreshTracks() {
        val mounted = Tracks.parse(mpv.getProperty("track-list") ?: "[]")
        ui.value = ui.value.copy(tracks = mounted.copy(audio = audioRows?.invoke(mounted.audio) ?: mounted.audio))
    }

    private fun isFullscreen() = fullscreenWindow?.active ?: cachedFullscreen

    private fun loop() {
        while (running) {
            try {
                tick()
            } catch (t: Throwable) {
                System.err.println("silage-hud: ${t.message}")
            }
            watchdog?.alive(System.currentTimeMillis())
            Thread.sleep(16)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now >= nextOsdCheck) {
            nextOsdCheck = now + 250
            cachedW = mpv.getProperty("osd-width")?.toIntOrNull() ?: 0
            cachedH = mpv.getProperty("osd-height")?.toIntOrNull() ?: 0
            if (fullscreenWindow == null) cachedFullscreen = mpv.getProperty("fullscreen") == "yes"
        }
        val w = cachedW
        val h = cachedH
        if (w <= 0 || h <= 0) return
        ensureScene(w, h)
        while (true) inbox.poll()?.invoke() ?: break
        flushPointerMove()
        if (nativeMouseMoved.getAndSet(false)) mousePos()?.let { pointerNow(PointerEventType.Move, it.x, it.y) }

        val s = mpv.state.value
        // Chapters are known once the file is loaded (it has a duration).
        if (!chaptersLoaded && s.durationSeconds > 0) {
            chaptersLoaded = true
            val chapters = Chapter.parse(mpv.getProperty("chapter-list"))
            ui.value = ui.value.copy(chapters = chapters)
            nav = nav.copy(hasChapters = chapters.isNotEmpty())
        }
        val skip = SkipPrompt.at(s.positionSeconds, ui.value.meta.intro, ui.value.meta.credits,
            hasNextEpisode = onNextEpisode != null && hasNextEpisode(), recap = ui.value.meta.recap)
        autoSkipAction(skip, autoSkip, autoSkipped)?.let { kind ->
            autoSkipped += if (kind == SkipPrompt.Kind.NextEpisode) SkipPrompt.Kind.Credits else kind
            if (kind == SkipPrompt.Kind.NextEpisode) actions.nextEpisode() else actions.seekTo(skip!!.target)
        }
        checkSleep(s.positionSeconds, knownDuration ?: s.durationSeconds, s.paused, now)
        if (ui.value.toast != null && now > toastUntil) ui.value = ui.value.copy(toast = null)
        // Hiding keeps the ring on a showing Skip button: Enter presses it.
        if (nav.visible && !visibility.visible(now, s.paused, nav.menu != HudMenu.None))
            nav = nav.copy(visible = false, focus = if (nav.hasSkip) HudFocus.Skip else HudFocus.PlayPause)
        watchBuffering(s, now)
        // A buffering offer takes the Skip button's place: Enter presses it.
        nav = nav.withSkip(skip != null || ui.value.qualityOffer != null)
        val visible = nav.visible
        hudVisible = visible
        val info = if (nav.menu == HudMenu.Info && now - lastInfoAt > 1_000) {
            lastInfoAt = now
            infoRows + InfoRows.build(mpv::getProperty) + liveInfoRows()
        } else {
            ui.value.info
        }
        val duration = knownDuration ?: s.durationSeconds
        // Rounded so a playing video redraws the whole HUD about once a second, not every frame.
        if (visible) {
            clock.position = hudPosition(s.positionSeconds, duration)
            if (now >= nextBufferCheck) {
                nextBufferCheck = now + 1_000
                clock.bufferedTo = kotlin.math.floor(mpv.getProperty("demuxer-cache-time")?.toDoubleOrNull() ?: 0.0)
            }
        }
        val next = ui.value.copy(
            duration = duration,
            paused = s.paused,
            buffering = s.buffering,
            visible = visible,
            menu = nav.menu,
            menuIndex = nav.menuIndex,
            focus = if (keyboardFocus && (visible || nav.focus == HudFocus.Skip)) nav.focus else null,
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
        } else if (!visible && next.skip == null && !next.buffering && next.toast == null && overlayShown && !sc.hasInvalidations()) {
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
        host = HudSceneHost(sw, sh, Density(sh / 1080f)) { HudContent(ui.value, actions, clock) }
        bitmap = Bitmap().apply { allocPixels(ImageInfo(sw, sh, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
        native = Memory(sw.toLong() * sh * 4)
    }

    private fun render(sc: ImageComposeScene, w: Int, h: Int) {
        val bmp = bitmap!!
        val target = native!!
        // Each render returns a new full-frame image. Closing it right away matters: left to
        // the garbage collector, these native frames piled up to gigabytes during playback.
        // The pixels are read straight into the overlay buffer, with no per-frame Java copy.
        val frame = sc.render(System.nanoTime())
        try {
            val pixmap = org.jetbrains.skia.Pixmap.make(bmp.imageInfo, Pointer.nativeValue(target), bmp.width * 4)
            try {
                if (!frame.readPixels(pixmap, 0, 0, false)) return
            } finally { pixmap.close() }
        } finally { frame.close() }
        mpv.addOverlay(0, Pointer.nativeValue(target), bmp.width, bmp.height, w, h)
        overlayShown = true
    }

    /** Idempotent: the player is closed more than once on the way out. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running = false
        previews?.close()
        scope.cancel()
        thread.join(1_000)
        runCatching { mpv.command("overlay-remove", "0") }
        host?.close()
    }
}
