package org.siloserver.silo.platform

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.repository.PlaybackJournalEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilePlaybackJournalStoreTest {
    private val dir = Files.createTempDirectory("silage-journal").resolve("playback-v2")
    private fun entry(id: String) = PlaybackJournalEntry(
        serverId = "s", origin = "https://nas", loginId = "l", accountId = "a", profileId = "p",
        installationId = "i", attemptId = id, start = JsonObject(mapOf("file_id" to JsonPrimitive(7))),
    )

    @Test fun emptyWhenNothingWritten() = runTest {
        assertTrue(FilePlaybackJournalStore(dir).read().isEmpty())
    }

    @Test fun entriesSurviveANewInstance() = runTest {
        val first = FilePlaybackJournalStore(dir)
        first.write(listOf(entry("a1"), entry("a2")))
        first.close()
        assertEquals(listOf("a1", "a2"), FilePlaybackJournalStore(dir).read().map { it.attemptId })
    }

    @Test fun secondLiveOwnerIsRejected() = runTest {
        val owner = FilePlaybackJournalStore(dir)
        owner.read()
        assertFailsWith<IllegalStateException> { FilePlaybackJournalStore(dir).read() }
        owner.close()
    }
}
