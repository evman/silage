package org.siloserver.silo.platform

import com.github.javakeyring.Keyring
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
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(tmp, contents)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.isSuccess
}

/** One secret in the desktop keyring (Secret Service: KWallet or GNOME Keyring). */
interface KeyringAccess {
    fun get(): String?
    fun set(value: String)

    companion object {
        const val SERVICE = "silage"
        const val ACCOUNT = "session"

        /** Null when no Secret Service is reachable on this session bus. */
        fun system(): KeyringAccess? = runCatching {
            val keyring = Keyring.create()
            // Probe once so a locked-out or absent backend fails here, not mid-sign-in.
            runCatching { keyring.getPassword(SERVICE, ACCOUNT) }
            object : KeyringAccess {
                override fun get(): String? = runCatching { keyring.getPassword(SERVICE, ACCOUNT) }.getOrNull()
                override fun set(value: String) = keyring.setPassword(SERVICE, ACCOUNT, value)
            }
        }.getOrNull()
    }
}

private class KeyringBlobStore(private val keyring: KeyringAccess) : BlobStore {
    override fun read(): String? = keyring.get()
    override fun write(contents: String): Boolean = runCatching { keyring.set(contents) }.isSuccess
}

object SecureBlobStore {
    /**
     * Keyring when available, otherwise [fallbackFile]. If the keyring is
     * empty but an older fallback file exists (keyring installed later), the
     * file's contents migrate into the keyring and the file is deleted.
     */
    fun create(
        fallbackFile: Path,
        keyring: KeyringAccess? = KeyringAccess.system(),
        log: (String) -> Unit = System.err::println,
    ): BlobStore {
        val file = FileBlobStore(fallbackFile)
        if (keyring == null) {
            log("Silage: no Secret Service keyring found; storing session in $fallbackFile (mode 0600)")
            return file
        }
        val store = KeyringBlobStore(keyring)
        if (store.read() == null) {
            file.read()?.let { legacy -> if (store.write(legacy)) Files.deleteIfExists(fallbackFile) }
        }
        return store
    }
}
