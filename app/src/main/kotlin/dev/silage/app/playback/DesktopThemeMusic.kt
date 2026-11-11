package dev.silage.app.playback

import dev.silage.player.EndReason
import dev.silage.player.MpvPlayer
import dev.silage.player.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.siloserver.silo.model.catalog.ThemeSelection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.ThemeAudioFormatV2
import org.siloserver.silo.network.apiv2.ThemeMusicV2Api
import org.siloserver.silo.playback.theme.ThemeAudio
import org.siloserver.silo.playback.theme.ThemeGrant
import org.siloserver.silo.playback.theme.ThemeMusicCoordinator
import org.siloserver.silo.playback.theme.ThemeMusicPlayer

/**
 * Theme music on title pages, as the other Silo apps play it: upstream's
 * [ThemeMusicCoordinator] and [ThemeMusicPlayer] (fades, looping, carrying a theme on
 * to the next page with the same set) over a small audio-only mpv of its own.
 * Off unless Settings → Interface → Theme music is on and the server serves themes.
 * Everything runs on the Swing thread, which the shared classes expect to own.
 */
class DesktopThemeMusic(
    private val api: ThemeMusicV2Api,
    private val tokens: TokenManager,
    registry: ServerRegistry,
    private val prefs: dev.silage.app.ui.UiPrefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    private val coordinator = ThemeMusicCoordinator(
        scope = scope,
        player = ThemeMusicPlayer(scope, ::grant, ::createAudio),
        capability = ::readCapability,
    )

    init {
        scope.launch { prefs.changes.collect { coordinator.setPreferences(prefs.themeMusic, prefs.themeMusicLoop) } }
        // Another server or account: stop, and ask the next one whether it serves themes.
        scope.launch {
            combine(registry.activeServerId, registry.activeAccountId) { s, a -> s to a }.drop(1).collect { coordinator.reset() }
        }
    }

    /** [page] is on screen with its themes (null until its details load). */
    fun show(page: Any, selection: ThemeSelection?) = scope.launch {
        trace("show: enabled=${prefs.themeMusic} themes=${selection?.items?.size ?: "none"}")
        coordinator.show(page, selection)
    }

    /** [page] left the screen. */
    fun hide(page: Any) = scope.launch { coordinator.hide(page) }

    /** A video is starting: end the theme now, so it starts over afterwards rather than resuming. */
    fun interrupt() = scope.launch { coordinator.interrupt() }

    /** The profile changed. */
    fun reset() = scope.launch { coordinator.reset() }

    private suspend fun readCapability(): Boolean? = when (val result = api.capability().also { trace("capability: ${(it as? ApiResult.Success)?.data?.let { d -> "available=${d.isAvailable}" } ?: (it as? ApiResult.Error)?.let { e -> "error ${e.code}" } ?: "network error"}") }) {
        is ApiResult.Success -> result.data.isAvailable
        // A server without the route predates themes.
        is ApiResult.Error -> if (result.code == 404) false else null
        is ApiResult.NetworkError -> null
    }

    private suspend fun grant(ownerId: String, themeId: String): ThemeGrant? {
        val owner = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() } ?: return null
        return when (val result = api.grant(ownerId, themeId, ACCEPTED, owner).also { trace("grant: ${(it as? ApiResult.Success)?.data?.let { d -> "ok converted=${d.isConverted}" } ?: (it as? ApiResult.Error)?.let { e -> "error ${e.code} ${e.error}" } ?: "network error"}") }) {
            is ApiResult.Success -> ThemeGrant(result.data.url, result.data.isConverted)
            else -> null
        }
    }

    private fun createAudio(url: String, loop: Boolean, listener: ThemeAudio.Listener): ThemeAudio =
        runCatching { MpvThemeAudio(url, loop, listener, scope) }.onFailure { trace("audio: mpv failed: $it") }.getOrThrow()

    companion object {
        /** FFmpeg (in mpv) decodes all of these, so the server sends originals and converts only the rest. */
        val ACCEPTED = listOf(
            ThemeAudioFormatV2("mp3", "mp3"), ThemeAudioFormatV2("mp4", "aac"), ThemeAudioFormatV2("m4a", "aac"),
            ThemeAudioFormatV2("aac", "aac"), ThemeAudioFormatV2("flac", "flac"), ThemeAudioFormatV2("ogg", "vorbis"),
            ThemeAudioFormatV2("ogg", "opus"), ThemeAudioFormatV2("opus", "opus"), ThemeAudioFormatV2("wav", "pcm"),
        )
    }
}

/** One theme on an audio-only mpv. The grant URL stays inside mpv; it is never logged. */
private class MpvThemeAudio(url: String, loop: Boolean, private val listener: ThemeAudio.Listener, parent: CoroutineScope) : ThemeAudio {
    private val mpv = MpvPlayer(mapOf(
        "vid" to "no", "vo" to "null", "force-window" to "no", "audio-display" to "no",
        "volume" to "0", "pause" to "yes", "keep-open" to "no", "ytdl" to "no", "cache" to "yes",
    ))
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
    private var wantPlaying = false
    private var released = false

    init {
        setLooping(loop)
        scope.launch {
            mpv.events.collect { event ->
                when (event) {
                    PlayerEvent.PlaybackRestart, PlayerEvent.FileLoaded -> { trace("audio: $event"); if (wantPlaying) listener.onPlaying() }
                    is PlayerEvent.Ended -> when (event.reason.also { trace("audio: ended ${event.reason} ${event.error.orEmpty()} ${event.log.takeLast(3)}") }) {
                        EndReason.EOF -> listener.onEnded()
                        EndReason.ERROR -> listener.onError()
                        else -> Unit
                    }
                    else -> Unit
                }
            }
        }
        // Resuming a paused, already-loaded theme makes no load event; report it from the pause state.
        scope.launch { mpv.state.map { it.loaded && !it.paused }.collect { audible -> if (audible && wantPlaying) listener.onPlaying() } }
        mpv.load(url)
    }

    override var volume: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            mpv.setProperty("volume", "%.1f".format(java.util.Locale.ROOT, field * 100))
        }

    override fun setLooping(loop: Boolean) { mpv.setProperty("loop-file", if (loop) "inf" else "no") }

    override fun play() { trace("audio: play"); wantPlaying = true; mpv.setPaused(false) }

    override fun pause() { wantPlaying = false; mpv.setPaused(true) }

    override fun release() {
        if (released) return
        released = true
        wantPlaying = false
        scope.cancel()
        // Closing waits for mpv's event thread; keep that off the UI thread.
        Thread({ runCatching { mpv.close() } }, "silage-theme-close").apply { isDaemon = true; start() }
    }
}

/** Theme music diagnostics: set SILAGE_THEME_TRACE to a file path. Never records grant URLs. */
internal fun trace(line: String) {
    val path = System.getenv("SILAGE_THEME_TRACE")?.takeIf { it.isNotBlank() } ?: return
    runCatching { java.io.File(path).appendText("${java.time.LocalTime.now()}  $line\n") }
}
