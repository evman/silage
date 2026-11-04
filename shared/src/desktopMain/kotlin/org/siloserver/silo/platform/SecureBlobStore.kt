package org.siloserver.silo.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Atomic file store, created with owner-only permissions. */
class FileBlobStore(private val path: Path) : BlobStore {
    override fun read(): String? = if (Files.exists(path)) Files.readString(path) else null

    override fun write(contents: String): Boolean = runCatching {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.deleteIfExists(tmp)
        if (Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            val owner = Files.getOwner(path.parent)
            val acl = listOf(java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission::class.java)).build())
            // Apply the restrictive DACL at creation, before any secret is written.
            Files.createFile(tmp, object : java.nio.file.attribute.FileAttribute<List<java.nio.file.attribute.AclEntry>> {
                override fun name() = "acl:acl"
                override fun value() = acl
            })
        }
        Files.writeString(tmp, contents)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.isSuccess
}

/**
 * One secret in the desktop keyring (Secret Service: KWallet or GNOME Keyring).
 * [get] yields null for "no entry" and throws when the keyring refuses access
 * (locked, prompt dismissed), so callers can tell locked from empty; [set] throws
 * when it can't save. The Linux app provides it (SecretServiceKeyring).
 */
interface KeyringAccess {
    fun get(): String?
    fun set(value: String)

    companion object {
        const val SERVICE = "silage"
        const val ACCOUNT = "session"
    }
}

/**
 * Keyring first; if the keyring refuses a write, switch to [file] for the rest
 * of the session (logged once) instead of losing the session. A keyring that
 * can't be read falls back to [file] rather than looking empty.
 */
private class KeyringBlobStore(
    private val keyring: KeyringAccess,
    private val file: FileBlobStore,
    private val fallbackFile: Path,
    private val log: (String) -> Unit,
) : SecureStore {
    @Volatile private var degraded = false
    override val inKeyring: Boolean get() = !degraded

    override fun read(): String? =
        if (degraded) file.read() else file.read() ?: runCatching { keyring.get() }.getOrNull()

    override fun write(contents: String): Boolean {
        if (!degraded) {
            try {
                keyring.set(contents)
                Files.deleteIfExists(fallbackFile)
                return true
            } catch (_: Exception) {
                degraded = true
                log("Silage: keyring refused to save the session; storing it in an owner-only file instead")
            }
        }
        return file.write(contents)
    }
}

/** The session store, saying where the sign-in is kept right now (Settings → About). */
interface SecureStore : BlobStore {
    val inKeyring: Boolean
}

object SecureBlobStore {
    /**
     * Keyring when available, otherwise [fallbackFile]. If the keyring is
     * available and a fallback exists, that newer fallback supersedes stale
     * keyring data and is retired only after a successful keyring write.
     */
    fun create(
        fallbackFile: Path,
        keyring: KeyringAccess?,
        log: (String) -> Unit = System.err::println,
    ): SecureStore {
        val file = FileBlobStore(fallbackFile)
        if (keyring == null) {
            log("Silage: no Secret Service keyring found; storing session in $fallbackFile (mode 0600)")
            return object : SecureStore, BlobStore by file { override val inKeyring = false }
        }
        val store = KeyringBlobStore(keyring, file, fallbackFile, log)
        // A fallback was written after keyring failure and must not be shadowed
        // by older readable keyring metadata at the next process startup.
        val keyringRead = runCatching { keyring.get() }
        if (keyringRead.isSuccess) {
            file.read()?.let { legacy ->
                if (runCatching { keyring.set(legacy) }.isSuccess) Files.deleteIfExists(fallbackFile)
            }
        }
        return store
    }
}
