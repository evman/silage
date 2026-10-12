package org.siloserver.silo.watchtogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion

class RoomVoteTest {
    @Test
    fun `winner is the first server-ordered suggestion with votes`() {
        val winner = suggestion("winner", votes = 3)
        val runnerUp = suggestion("runner-up", votes = 2)

        assertEquals(winner, roomVoteWinner(listOf(winner, runnerUp)))
    }

    @Test
    fun `room with no votes has no winner`() {
        assertNull(roomVoteWinner(listOf(suggestion("oldest", votes = 0))))
        assertNull(roomVoteWinner(emptyList()))
    }

    @Test
    fun `vote-room predicate follows the snapshot selection mode`() {
        assertTrue(RoomSnapshot(roomId = "room", selectionMode = RoomSelectionMode.Vote).isVoteRoom())
        assertFalse(RoomSnapshot(roomId = "room", selectionMode = RoomSelectionMode.HostPick).isVoteRoom())
        assertFalse(null.isVoteRoom())
    }

    private fun suggestion(id: String, votes: Int) = Suggestion(
        id = id,
        roomId = "room",
        contentId = "content-$id",
        contentType = "movie",
        title = id,
        voteCount = votes,
        createdAt = "2026-07-27T00:00:00Z",
    )
}
