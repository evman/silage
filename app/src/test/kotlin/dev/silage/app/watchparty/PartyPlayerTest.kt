package dev.silage.app.watchparty

import dev.silage.player.PlayerState
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.watchtogether.RoomPlaybackNotice
import org.siloserver.silo.watchtogether.RoomPlayerState
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyPlayerTest {
    private fun observe(s: PlayerState, seekPending: Boolean = false, atEnd: Boolean = false) =
        roomObservation(s, "session", 7, timelineOffset = 42.0, duration = 3000.0, seekPending = seekPending, atEnd = atEnd)

    @Test fun positionsAreOnTheSourcesClockSoEveryViewerAgrees() {
        val o = observe(PlayerState(loaded = true, positionSeconds = 8.0))
        assertEquals(50.0, o.sourcePositionSeconds)
        assertEquals("session", o.sessionId)
        assertEquals(7, o.selectionRevision)
    }

    @Test fun playerStatesMapToWhatTheRoomExpects() {
        assertEquals(RoomPlayerState.Idle, observe(PlayerState(loaded = false)).state)
        assertEquals(RoomPlayerState.Ready, observe(PlayerState(loaded = true)).state)
        assertEquals(RoomPlayerState.Buffering, observe(PlayerState(loaded = true, buffering = true)).state)
        assertEquals(RoomPlayerState.Ended, observe(PlayerState(loaded = true, paused = true), atEnd = true).state)
    }

    @Test fun onlyAnUnhinderedPlayerCountsAsPlaying() {
        assertTrue(observe(PlayerState(loaded = true, paused = false)).isPlaying)
        assertFalse(observe(PlayerState(loaded = true, paused = true)).isPlaying)
        assertFalse(observe(PlayerState(loaded = true, buffering = true)).isPlaying)
        assertFalse(observe(PlayerState(loaded = true), seekPending = true).isPlaying)
        assertFalse(observe(PlayerState(loaded = true, paused = true)).playWhenReady)
    }

    private fun member(name: String, self: Boolean = false, buffering: Boolean = false) =
        RoomMember(displayName = name, isSelf = self, connected = true, isReady = !buffering, isBuffering = buffering)

    @Test fun theLineSaysWhoTheRoomIsWaitingFor() {
        val room = RoomSnapshot("r", phase = RoomPhase.Playing, members = listOf(member("Me", self = true), member("Sam")))
        assertEquals("Watching together · 2 here", partyLine(room, catchingUp = false))
        assertEquals("Watching together · catching up", partyLine(room, catchingUp = true))
        val waiting = room.copy(members = listOf(member("Me", self = true), member("Sam", buffering = true)))
        assertEquals("Watching together · waiting for Sam", partyLine(waiting, catchingUp = false))
        val alone = room.copy(members = listOf(member("Me", self = true)))
        assertEquals("Watching together", partyLine(alone, catchingUp = false))
    }

    @Test fun refusalsExplainThemselves() {
        assertEquals("Only the host can skip", partyNoticeText(RoomPlaybackNotice.Denied(RoomTransportIntent.Seek)))
        assertEquals("Only the host can play and pause", partyNoticeText(RoomPlaybackNotice.Denied(RoomTransportIntent.PlayPause)))
    }

    @Test fun theLastTitleOpenedIsntOpenedAgain() {
        val memory = PartyPlayerMemory()
        assertTrue(memory.shouldOpen("r", 1))
        assertFalse(memory.shouldOpen("r", 1))
        assertTrue(memory.shouldOpen("r", 2))
        assertTrue(memory.shouldOpen("other", 2))
    }

    @Test fun availabilityExplainsWhyAPartyCantHappen() {
        assertNotNull(partyUnavailableText(WatchPartyAvailability.NotAllowed))
        assertNotNull(partyUnavailableText(WatchPartyAvailability.Unsupported(listOf("rooms"))))
        assertNull(partyUnavailableText(null))
    }
}
