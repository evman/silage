package dev.silage.app.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.port.HomeCachePort
import org.siloserver.silo.repository.port.HomeCacheSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Home as last loaded, one file per server, account and profile, so Silage opens on Home's rows
 * at once and refreshes them from the server behind (upstream's stale-while-revalidate; Android
 * keeps the same cache in Room). Titles and artwork links only; nothing that signs anyone in.
 */
class FileHomeCache(private val dir: Path) : HomeCachePort {
    @Serializable
    private data class Saved(val cachedAtMs: Long, val artwork: String? = null, val sections: List<ResolvedSection>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private fun fileFor(owner: AuthScopeSnapshot): Path? {
        val profile = owner.profileId?.takeIf { it.isNotBlank() } ?: return null
        // Hashed, so ids never become file names as they are.
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${owner.serverId}|${owner.accountId.orEmpty()}|$profile".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return dir.resolve("home-$key.json")
    }

    override suspend fun getCachedHomeV2(owner: AuthScopeSnapshot): HomeCacheSnapshot? = withContext(Dispatchers.IO) {
        val file = fileFor(owner)?.takeIf { Files.isRegularFile(it) } ?: return@withContext null
        runCatching { json.decodeFromString(Saved.serializer(), Files.readString(file)) }.getOrNull()
            ?.let { HomeCacheSnapshot(it.sections, it.cachedAtMs) }
    }

    override suspend fun cacheHomeV2(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, artworkFingerprint: String?, stillCurrent: () -> Boolean) {
        write(owner, sections, artworkFingerprint, replace = true, stillCurrent)
    }

    override suspend fun cacheHomeV2IfAbsent(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, artworkFingerprint: String?, stillCurrent: () -> Boolean) {
        write(owner, sections, artworkFingerprint, replace = false, stillCurrent)
    }

    private suspend fun write(owner: AuthScopeSnapshot, sections: List<ResolvedSection>, artwork: String?, replace: Boolean, stillCurrent: () -> Boolean) =
        withContext(Dispatchers.IO) {
            val file = fileFor(owner) ?: return@withContext
            if (sections.isEmpty() || !stillCurrent()) return@withContext
            if (!replace && Files.exists(file)) return@withContext
            runCatching {
                Files.createDirectories(dir)
                val tmp = Files.createTempFile(dir, "home", ".tmp")
                Files.writeString(tmp, json.encodeToString(Saved.serializer(), Saved(System.currentTimeMillis(), artwork, sections)))
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            Unit
        }
}
