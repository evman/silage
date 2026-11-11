package dev.silage.app.downloads

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.repository.port.DownloadDeletionPort
import org.siloserver.silo.repository.port.PendingDownloadDeletion
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
private data class SavedDeletion(
    val serverId: String, val profileId: String, val recordId: String, val mediaFileId: Int? = null,
    val loginId: String? = null, val origin: String? = null, val deviceId: String? = null,
    // Added with multi-account support; rows saved before it have "" and match any account.
    val accountId: String = "",
) {
    fun matches(serverId: String, accountId: String, profileId: String) =
        this.serverId == serverId && this.profileId == profileId && (this.accountId.isEmpty() || this.accountId == accountId)
}

/**
 * Downloads removed here that the server hasn't heard about yet, in a file, so
 * one removed with no connection doesn't come back later.
 */
class FileDownloadDeletions(
    private val file: Path,
    private val authorities: DurableLoginAuthorityProvider,
    private val devices: DeviceMetadataProvider,
) : DownloadDeletionPort {
    private val lock = Mutex()
    private val serializer = ListSerializer(SavedDeletion.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private fun read(): List<SavedDeletion> = runCatching { json.decodeFromString(serializer, Files.readString(file)) }.getOrDefault(emptyList())

    private fun write(rows: List<SavedDeletion>) {
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "deletions", ".tmp")
        Files.writeString(temp, json.encodeToString(serializer, rows))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun enqueue(serverId: String, accountId: String, profileId: String, recordId: String, mediaFileId: Int?) {
        val authority = authorities.snapshotDurableLoginAuthority()
        val device = devices.current()?.id
        lock.withLock {
            write(read().filterNot { it.matches(serverId, accountId, profileId) && it.recordId == recordId } +
                SavedDeletion(serverId, profileId, recordId, mediaFileId, authority?.loginId, authority?.scope?.serverUrl, device, accountId))
        }
    }

    override suspend fun allPendingRecordIds(): Set<String> = lock.withLock { read().mapTo(HashSet()) { it.recordId } }

    override suspend fun pendingForScope(serverId: String, accountId: String, profileId: String): List<PendingDownloadDeletion> = lock.withLock {
        read().filter { it.matches(serverId, accountId, profileId) }
            .map { PendingDownloadDeletion(it.serverId, accountId, it.profileId, it.recordId, it.mediaFileId, it.loginId, it.origin, it.deviceId) }
    }

    override suspend fun remove(serverId: String, accountId: String, profileId: String, recordId: String) = lock.withLock {
        write(read().filterNot { it.matches(serverId, accountId, profileId) && it.recordId == recordId })
    }
}
