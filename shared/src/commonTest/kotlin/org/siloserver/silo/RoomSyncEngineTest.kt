package org.siloserver.silo

import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.TransportAction
import org.siloserver.silo.model.watchtogether.TransportCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSyncEngineTest {

    private fun cmd(
        id: String = "cmd-1",
        sessionId: String = "sess-1",
        revision: Long = 1L,
        action: TransportAction = TransportAction.Play,
        positionSeconds: Double = 0.0,
        executeAt: String = "2026-06-12T09:30:00.000000000Z",
        playbackState: RoomPlaybackState = RoomPlaybackState.Playing,
    ) = TransportCommand(
        commandId = id,
        sessionId = sessionId,
        selectionRevision = revision,
        action = action,
        positionSeconds = positionSeconds,
        executeAt = executeAt,
        issuedAt = "2026-06-12T09:29:59.000000000Z",
        playbackState = playbackState,
    )

    // ---- offset from ping/pong samples ----------------------------------------

    @Test
    fun `offset uses NTP midpoint formula`() {
        val engine = RoomSyncEngine()
        // client_sent=0, server_received=100, server_sent=110, client_received=20
        // offset = ((100-0)+(110-20))/2 = (100+90)/2 = 95
        engine.recordPongSample(
            clientSentMs = 0L, serverReceivedMs = 100L, serverSentMs = 110L, clientReceivedMs = 20L,
        )
        assertEquals(95L, engine.serverTimeOffsetMs)
    }

    @Test
    fun `offset updates to latest sample`() {
        val engine = RoomSyncEngine()
        engine.recordPongSample(0L, 100L, 110L, 20L) // 95
        engine.recordPongSample(0L, 50L, 52L, 4L)    // ((50-0)+(52-4))/2 = (50+48)/2 = 49
        assertEquals(49L, engine.serverTimeOffsetMs)
    }

    @Test
    fun `offset is null before any sample`() {
        assertNull(RoomSyncEngine().serverTimeOffsetMs)
    }

    // ---- schedule-time math ---------------------------------------------------

    @Test
    fun `local execute delay is executeAt minus offset minus now`() {
        val engine = RoomSyncEngine()
        // offset = +95ms means server clock is 95ms ahead of local.
        engine.recordPongSample(0L, 100L, 110L, 20L) // 95
        // executeAt server epoch ms. Choose a known instant. Use the helper that
        // accepts an explicit serverExecuteAtMs to keep RFC3339 parsing out of math.
        val decision = engine.decide(
            command = cmd(action = TransportAction.Play),
            serverExecuteAtMs = 10_000L,
            currentLocalPositionMs = 0L,
            currentIsPlaying = false,
            nowLocalMs = 5_000L,
            currentRevision = 1L,
            attachedSessionId = "sess-1",
        )!!
        // localExecute = serverExecuteAt - offset = 10000 - 95 = 9905; delay = 9905 - 5000 = 4905
        assertEquals(4905L, decision.localExecuteDelayMs)
    }

    @Test
    fun `negative computed delay is clamped to zero`() {
        val engine = RoomSyncEngine()
        engine.recordPongSample(0L, 0L, 0L, 0L) // offset 0
        val decision = engine.decide(
            command = cmd(),
            serverExecuteAtMs = 1_000L,
            currentLocalPositionMs = 0L,
            currentIsPlaying = false,
            nowLocalMs = 5_000L, // already past executeAt
            currentRevision = 1L,
            attachedSessionId = "sess-1",
        )!!
        assertEquals(0L, decision.localExecuteDelayMs)
    }

    @Test
    fun `no offset yet applies immediately with delay zero (safe fallback)`() {
        val engine = RoomSyncEngine()
        val decision = engine.decide(
            command = cmd(),
            serverExecuteAtMs = 10_000L,
            currentLocalPositionMs = 0L,
            currentIsPlaying = false,
            nowLocalMs = 5_000L,
            currentRevision = 1L,
            attachedSessionId = "sess-1",
        )!!
        assertEquals(0L, decision.localExecuteDelayMs)
    }

    // ---- dedupe ---------------------------------------------------------------

    @Test
    fun `duplicate command_id is ignored`() {
        val engine = RoomSyncEngine()
        val first = engine.decide(cmd(id = "c1"), 0L, 0L, false, 0L, 1L, "sess-1")
        assertTrue(first != null)
        val dup = engine.decide(cmd(id = "c1"), 0L, 0L, false, 0L, 1L, "sess-1")
        assertNull(dup)
    }

    @Test
    fun `new command_id after a duplicate is accepted`() {
        val engine = RoomSyncEngine()
        engine.decide(cmd(id = "c1"), 0L, 0L, false, 0L, 1L, "sess-1")
        val next = engine.decide(cmd(id = "c2"), 0L, 0L, false, 0L, 1L, "sess-1")
        assertTrue(next != null)
    }

    // ---- revision / session gating --------------------------------------------

    @Test
    fun `command with mismatched selection_revision is ignored`() {
        val engine = RoomSyncEngine()
        assertNull(engine.decide(cmd(revision = 2L), 0L, 0L, false, 0L, 1L, "sess-1"))
    }

    @Test
    fun `command with mismatched session_id is ignored`() {
        val engine = RoomSyncEngine()
        assertNull(engine.decide(cmd(sessionId = "other"), 0L, 0L, false, 0L, 1L, "sess-1"))
    }

    @Test
    fun `command with blank session_id is accepted regardless of attached session`() {
        // Solo/late-join targeted seeks may omit session_id; only mismatched non-blank gates.
        val engine = RoomSyncEngine()
        val d = engine.decide(cmd(sessionId = ""), 0L, 0L, false, 0L, 1L, "sess-1")
        assertTrue(d != null)
    }

    // ---- 0.35s corrective-seek threshold --------------------------------------

    @Test
    fun `play command with drift under threshold does not seek`() {
        val engine = RoomSyncEngine()
        // command.position = 10.0s = 10000ms; local = 10200ms; drift 200ms < 350ms
        val d = engine.decide(
            cmd(action = TransportAction.Play, positionSeconds = 10.0),
            serverExecuteAtMs = 0L, currentLocalPositionMs = 10_200L,
            currentIsPlaying = false, nowLocalMs = 0L, currentRevision = 1L, attachedSessionId = "sess-1",
        )!!
        assertNull(d.seekToMs)
        assertTrue(d.setPlaying)
    }

    @Test
    fun `play command with drift just over threshold seeks`() {
        val engine = RoomSyncEngine()
        // drift 360ms > 350ms
        val d = engine.decide(
            cmd(action = TransportAction.Play, positionSeconds = 10.0),
            0L, 10_360L, false, 0L, 1L, "sess-1",
        )!!
        assertEquals(10_000L, d.seekToMs)
    }

    @Test
    fun `drift threshold is symmetric (local behind)`() {
        val engine = RoomSyncEngine()
        // local 9640ms vs command 10000ms → drift 360ms
        val d = engine.decide(
            cmd(action = TransportAction.Pause, positionSeconds = 10.0),
            0L, 9_640L, true, 0L, 1L, "sess-1",
        )!!
        assertEquals(10_000L, d.seekToMs)
        assertTrue(!d.setPlaying) // pause
    }

    @Test
    fun `seek action always seeks even when drift is tiny`() {
        val engine = RoomSyncEngine()
        val d = engine.decide(
            cmd(action = TransportAction.Seek, positionSeconds = 10.0),
            0L, 10_050L, false, 0L, 1L, "sess-1",
        )!!
        assertEquals(10_000L, d.seekToMs) // forced seek despite 50ms drift
    }

    // ---- play / pause mapping --------------------------------------------------

    @Test
    fun `play sets playing true, pause sets playing false`() {
        val engine = RoomSyncEngine()
        val play = engine.decide(cmd(id = "p", action = TransportAction.Play), 0L, 0L, false, 0L, 1L, "sess-1")!!
        assertTrue(play.setPlaying)
        val pause = engine.decide(cmd(id = "q", action = TransportAction.Pause), 0L, 0L, true, 0L, 1L, "sess-1")!!
        assertTrue(!pause.setPlaying)
    }

    @Test
    fun `seek command sets playing from playback_state playing`() {
        val engine = RoomSyncEngine()
        val d = engine.decide(
            cmd(action = TransportAction.Seek, playbackState = RoomPlaybackState.Playing, positionSeconds = 5.0),
            0L, 0L, false, 0L, 1L, "sess-1",
        )!!
        assertTrue(d.setPlaying)
    }

    @Test
    fun `seek command stays paused when playback_state paused`() {
        val engine = RoomSyncEngine()
        val d = engine.decide(
            cmd(action = TransportAction.Seek, playbackState = RoomPlaybackState.Paused, positionSeconds = 5.0),
            0L, 0L, true, 0L, 1L, "sess-1",
        )!!
        assertTrue(!d.setPlaying)
    }

    // ---- ready emission --------------------------------------------------------

    @Test
    fun `shouldEmitReady true when playback_state is waiting`() {
        val engine = RoomSyncEngine()
        val d = engine.decide(
            cmd(playbackState = RoomPlaybackState.Waiting),
            0L, 0L, false, 0L, 1L, "sess-1",
        )!!
        assertTrue(d.shouldEmitReady)
    }

    @Test
    fun `shouldEmitReady false when not waiting`() {
        val engine = RoomSyncEngine()
        val d = engine.decide(
            cmd(playbackState = RoomPlaybackState.Playing),
            0L, 0L, false, 0L, 1L, "sess-1",
        )!!
        assertTrue(!d.shouldEmitReady)
    }
}
