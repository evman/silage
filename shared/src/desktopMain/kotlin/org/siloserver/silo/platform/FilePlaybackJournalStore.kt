package org.siloserver.silo.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.repository.PlaybackJournalEntry
import org.siloserver.silo.repository.PlaybackJournalStore
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Desktop counterpart of Android's AndroidPlaybackJournalStore: the playback
 * request journal lives in [directory] and only one live Silage process may
 * own it, because a second process must not replay the same requests.
 * Nothing touches disk until the first read or write.
 */
class FilePlaybackJournalStore(private val directory: Path) : PlaybackJournalStore, Closeable {
    private val file = FileBlobStore(directory.resolve("requests.json"))
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    @Synchronized
    private fun ensureOwner() {
        if (lock?.isValid == true) return
        Files.createDirectories(directory)
        val ch = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired = try { ch.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (acquired == null) {
            ch.close()
            throw IllegalStateException("Playback journal has another process owner")
        }
        channel = ch
        lock = acquired
    }

    override suspend fun read(): List<PlaybackJournalEntry> = withContext(Dispatchers.IO) {
        ensureOwner()
        file.read()?.let { SiloJson.decodeFromString<List<PlaybackJournalEntry>>(it) } ?: emptyList()
    }

    override suspend fun write(entries: List<PlaybackJournalEntry>) = withContext(Dispatchers.IO) {
        ensureOwner()
        check(file.write(SiloJson.encodeToString(entries))) { "Couldn't write playback journal" }
    }

    @Synchronized
    override fun close() {
        lock?.release(); channel?.close()
        lock = null; channel = null
    }
}
