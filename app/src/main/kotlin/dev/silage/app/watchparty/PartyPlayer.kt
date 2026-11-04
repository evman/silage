package dev.silage.app.watchparty

import dev.silage.app.playback.LaunchResult
import dev.silage.app.playback.hud.PartyTransport
import dev.silage.app.playback.hud.PlayerHud
import dev.silage.player.MpvPlayer
import dev.silage.player.PlayerEvent
import dev.silage.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RoomPlaybackBinding
import org.siloserver.silo.watchtogether.RoomPlaybackNotice
import org.siloserver.silo.watchtogether.RoomPlayerObservation
import org.siloserver.silo.watchtogether.RoomPlayerPort
import org.siloserver.silo.watchtogether.RoomPlayerState
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.RoomTransportResult
import org.siloserver.silo.watchtogether.WatchPartySeatState
import org.siloserver.silo.watchtogether.watchPartySeatState
import java.util.Locale

/** What the room should know of mpv's state. The position is on the source's clock, which every viewer shares. */
internal fun roomObservation(
    s: PlayerState, sessionId: String, selectionRevision: Long, timelineOffset: Double, duration: Double,
    seekPending: Boolean, atEnd: Boolean,
): RoomPlayerObservation = RoomPlayerObservation(
    sessionId = sessionId,
    selectionRevision = selectionRevision,
    sourcePositionSeconds = s.positionSeconds + timelineOffset,
    durationSeconds = duration,
    playWhenReady = !s.paused,
    isPlaying = s.loaded && !s.paused && !s.buffering && !seekPending && !atEnd,
    state = when {
        !s.loaded -> RoomPlayerState.Idle
        atEnd -> RoomPlayerState.Ended
        s.buffering -> RoomPlayerState.Buffering
        else -> RoomPlayerState.Ready
    },
    seekPending = seekPending,
)

/** The line under the title in the player: how many are here, and anyone the room is waiting for. */
internal fun partyLine(room: RoomSnapshot?, catchingUp: Boolean): String {
    if (catchingUp) return "Watching together · catching up"
    val here = room?.members.orEmpty().filter { it.connected }
    val waiting = room?.takeIf { it.phase == RoomPhase.Playing }?.members.orEmpty().filter {
        it.connected && !it.isSelf && watchPartySeatState(it, RoomPhase.Playing).let { s -> s == WatchPartySeatState.Buffering || s == WatchPartySeatState.Syncing }
    }
    return when {
        waiting.size == 1 -> "Watching together · waiting for ${waiting[0].displayName.ifBlank { "someone" }}"
        waiting.isNotEmpty() -> "Watching together · waiting for ${waiting.size} people"
        here.size > 1 -> "Watching together · ${here.size} here"
        else -> "Watching together"
    }
}

/** What to say about a refused or undelivered request. */
internal fun partyNoticeText(notice: RoomPlaybackNotice): String = when (notice) {
    is RoomPlaybackNotice.Denied ->
        if (notice.intent == RoomTransportIntent.Seek) "Only the host can skip" else "Only the host can play and pause"
    RoomPlaybackNotice.Reconnecting -> "Reconnecting to the party…"
    RoomPlaybackNotice.ClockUnavailable -> "Waiting to sync with the party…"
    RoomPlaybackNotice.Undelivered -> "That didn't reach the party. Try again."
}

/** The room drives mpv through this: seeks on the source's clock, and a rate only ever nudged for drift. */
internal class MpvRoomPlayer(
    private val mpv: MpvPlayer,
    private val ready: LaunchResult.Ready,
    private val selectionRevision: Long,
    scope: CoroutineScope,
) : RoomPlayerPort {
    private val duration get() = ready.sourceDurationSeconds ?: (mpv.state.value.durationSeconds + ready.timelineOffsetSeconds)
    private var seekPending = true
    private var seekStartedAt = System.currentTimeMillis()
    private val _observations = MutableStateFlow(observe())
    override val observations: StateFlow<RoomPlayerObservation> = _observations.asStateFlow()

    init {
        scope.launch {
            mpv.events.collect { e ->
                if (e is PlayerEvent.PlaybackRestart) { seekPending = false; publish() }
            }
        }
        scope.launch { mpv.state.collect { publish() } }
        scope.launch {
            // mpv can drop a restart event; a lost one mustn't hold the room's commands forever.
            while (true) {
                delay(1_000)
                if (seekPending && System.currentTimeMillis() - seekStartedAt > SeekGiveUpMs) { seekPending = false; publish() }
            }
        }
    }

    private fun observe(): RoomPlayerObservation {
        val s = mpv.state.value
        // keep-open holds the last frame at the end, so the room can still be told about it.
        val atEnd = s.loaded && s.durationSeconds > 0 && s.positionSeconds >= s.durationSeconds - 1.0 && mpv.getProperty("eof-reached") == "yes"
        return roomObservation(s, ready.sessionId, selectionRevision, ready.timelineOffsetSeconds, duration, seekPending, atEnd)
    }

    private fun publish() { _observations.value = observe() }

    override fun seekTo(sourceSeconds: Double) {
        seekPending = true
        seekStartedAt = System.currentTimeMillis()
        publish()
        mpv.seekAbsolute((sourceSeconds - ready.timelineOffsetSeconds).coerceAtLeast(0.0))
    }

    override fun setPlaying(playing: Boolean) = mpv.setPaused(!playing)

    override fun setCorrectionRate(rate: Double?) {
        mpv.setProperty("speed", "%.3f".format(Locale.ROOT, rate ?: 1.0))
    }

    override fun isBuffered(sourceSeconds: Double): Boolean {
        val target = sourceSeconds - ready.timelineOffsetSeconds
        val position = mpv.state.value.positionSeconds
        val cachedTo = mpv.getProperty("demuxer-cache-time")?.toDoubleOrNull() ?: return false
        return target in position..cachedTo
    }

    private companion object {
        const val SeekGiveUpMs = 15_000L
    }
}

/**
 * One playback epoch of a room: binds a player to it for the life of that
 * player. Leaving the player ends this, not the membership: the room's socket
 * belongs to [org.siloserver.silo.watchtogether.RoomSession].
 */
internal class PartyPlayback(
    private val room: WatchTogetherRepository,
    mpv: MpvPlayer,
    private val ready: LaunchResult.Ready,
    selectionRevision: Long,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binding = RoomPlaybackBinding(
        room, MpvRoomPlayer(mpv, ready, selectionRevision, scope), scope,
        monotonicNowMs = { System.nanoTime() / 1_000_000 },
        wallNowMs = System::currentTimeMillis,
    )

    /** Play, pause and seek as requests to the room; the player moves when its command arrives. */
    val transport = object : PartyTransport {
        // A refusal is announced through the binding's notices.
        override fun playPause(pause: Boolean): String? = binding.requestPlayPause(pause).let(::message)
        override fun seekTo(seconds: Double): String? = binding.requestSeek(ready.sourcePositionSeconds(seconds)).let(::message)
    }

    private fun message(result: RoomTransportResult): String? =
        if (result == RoomTransportResult.NotInRoom) "The party isn't playing right now" else null

    /** Starts syncing; [hud] shows what the room is doing. */
    fun start(hud: PlayerHud) {
        scope.launch { binding.notices.collect { hud.notice(partyNoticeText(it)) } }
        scope.launch {
            kotlinx.coroutines.flow.combine(room.roomSnapshot, binding.catchingUp) { snapshot, catching -> partyLine(snapshot, catching) }
                .collect { hud.partyLine(it) }
        }
        binding.start()
    }

    override fun close() {
        binding.dispose()
        scope.cancel()
    }
}
