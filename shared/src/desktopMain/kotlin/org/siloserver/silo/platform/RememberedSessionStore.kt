package org.siloserver.silo.platform

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Base64

/** Current Windows user DPAPI only. No machine-wide key or plaintext fallback. */
interface SessionCipher {
    fun protect(bytes: ByteArray): ByteArray
    fun unprotect(bytes: ByteArray): ByteArray
}

object WindowsSessionCipher : SessionCipher {
    private val entropy = "Silage remembered sign-in v1".toByteArray(Charsets.UTF_8)
    override fun protect(bytes: ByteArray): ByteArray = Crypt32Util.cryptProtectData(
        bytes, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Silage sign-in", null)
    override fun unprotect(bytes: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(
        bytes, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
}

/**
 * One atomic owner-only file: non-secret registry/preferences plus DPAPI ciphertext.
 * PersistentPreferences keeps the complete live session in memory; this boundary
 * deliberately persists authentication only for servers with explicit consent.
 * Account replacement/sign-out/invalidation still use the existing atomic editor.
 */
class RememberedSessionStore(
    path: Path,
    private val cipher: SessionCipher = WindowsSessionCipher,
    private val file: BlobStore = FileBlobStore(path),
) : BlobStore {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    override fun read(): String? {
        return readSafely()
    }

    private fun readSafely(): String? { return try {
        val raw = file.read()
        if (raw == null) null else {
            val envelope = Json.parseToJsonElement(raw).jsonObject
            // Legacy plaintext never implied consent. Retire it atomically now,
            // even if the user closes the app before making another preference edit.
            if (envelope["version"] == null) {
                val metadata = JsonObject(envelope.filterKeys(::isPublic)).toString()
                if (!write(metadata)) _error.value = WRITE_ERROR
                return metadata
            }
            require(envelope["version"]?.jsonPrimitive?.int == 1)
            val public = envelope.getValue("public").jsonObject
            require(public.keys.all(::isPublic))
            val encoded = envelope["protected"]?.jsonPrimitive?.content
            val secrets = if (encoded == null) emptyMap() else try {
                val bytes = cipher.unprotect(Base64.getDecoder().decode(encoded))
                try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
                finally { bytes.fill(0) }
            } catch (_: Exception) {
                _error.value = READ_ERROR
                emptyMap()
            } catch (_: LinkageError) {
                _error.value = READ_ERROR
                emptyMap()
            }
            JsonObject(public + secrets.filterKeys { !isPublic(it) && enabledFor(it, public) }).toString()
        }
    } catch (_: Exception) {
        _error.value = READ_ERROR
        // Never return unverified or plaintext authentication. No backend fallback.
        null
    } catch (_: LinkageError) {
        _error.value = READ_ERROR
        null
    } }

    override fun write(contents: String): Boolean = try {
        val all = Json.parseToJsonElement(contents).jsonObject
        val public = all.filterKeys(::isPublic)
        val retained = all.filterKeys { !isPublic(it) && enabledFor(it, all) }
        val envelope = mutableMapOf<String, JsonElement>(
            "version" to JsonPrimitive(1), "public" to JsonObject(public))
        if (retained.isNotEmpty()) {
            val bytes = JsonObject(retained).toString().toByteArray(Charsets.UTF_8)
            val encrypted = try { cipher.protect(bytes) } finally { bytes.fill(0) }
            envelope["protected"] = JsonPrimitive(Base64.getEncoder().encodeToString(encrypted))
        }
        if (file.write(JsonObject(envelope).toString())) {
            _error.value = null
            true
        } else {
            _error.value = WRITE_ERROR
            false
        }
    } catch (_: Exception) {
        _error.value = WRITE_ERROR
        false
    } catch (_: LinkageError) {
        _error.value = WRITE_ERROR
        false
    }

    companion object {
        const val REMEMBER = "remember_sign_in"
        const val READ_ERROR = "Saved sign-in could not be unlocked. Sign in again, or turn off Remember me to forget it. Use the same Windows account."
        const val WRITE_ERROR = "Windows could not securely save sign-in. Check access to the Silage data folder, or turn off Remember me and sign in for this session only."
        private fun isPublic(key: String): Boolean = key == "server_registry.v1" ||
            key == "server_registry.migrated.v1" || key == "server_url" || key.endsWith(".$REMEMBER")
        private fun enabledFor(key: String, values: Map<String, JsonElement>): Boolean {
            val server = key.substringBeforeLast('.', "")
            if (server.isEmpty()) return false
            return values["$server.$REMEMBER"]?.jsonObject?.get("b")?.jsonPrimitive?.booleanOrNull == true
        }
    }
}
