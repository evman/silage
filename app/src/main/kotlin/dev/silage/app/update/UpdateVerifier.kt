package dev.silage.app.update

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Checks a downloaded update against the release's `SHA256SUMS`, which must carry a valid
 * Ed25519 signature (`SHA256SUMS.sig`, base64) by the release key ([publicKeyPem], X.509 PEM).
 */
class UpdateVerifier(publicKeyPem: String) {
    private val key: PublicKey = run {
        val body = publicKeyPem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(body)))
    }

    /** True only when [sums] is signed by the release key and lists [fileName] once, with [file]'s SHA-256. */
    fun verify(sums: ByteArray, signatureBase64: String, fileName: String, file: Path): Boolean {
        val signature = try {
            Base64.getDecoder().decode(signatureBase64.trim())
        } catch (_: IllegalArgumentException) {
            return false
        }
        val signed = try {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(sums)
                verify(signature)
            }
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
        if (!signed) return false
        // Lines are "<sha256 hex>  <name>" (sha256sum's text mode; "*<name>" in binary mode).
        val listed = sums.decodeToString().lines().mapNotNull { line ->
            val hash = line.substringBefore(' ')
            val name = line.substringAfter(' ', "").removePrefix(" ").removePrefix("*")
            hash.takeIf { name == fileName && it.length == 64 }
        }
        val expected = listed.singleOrNull()?.lowercase() ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), sha256Hex(file).toByteArray())
    }

    companion object {
        /** The verifier for real releases, with the key bundled as the `/update-key.pub` resource. */
        fun bundled(): UpdateVerifier {
            val pem = UpdateVerifier::class.java.getResourceAsStream("/update-key.pub")
                ?.use { it.readBytes().decodeToString() }
                ?: error("The update key is missing from the build")
            return UpdateVerifier(pem)
        }

        private fun sha256Hex(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
