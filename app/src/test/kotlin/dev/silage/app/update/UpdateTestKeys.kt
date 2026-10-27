package dev.silage.app.update

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/** A throwaway Ed25519 key pair and helpers to sign a SHA256SUMS like scripts/sign-release.sh does. */
class UpdateTestKeys {
    val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    val publicKeyPem: String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"

    fun sign(bytes: ByteArray): String {
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(pair.private)
            update(bytes)
        }.sign()
        return Base64.getEncoder().encodeToString(signature)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sums(vararg files: Pair<String, ByteArray>): ByteArray =
            files.joinToString("") { (name, bytes) -> "${sha256Hex(bytes)}  $name\n" }.toByteArray()
    }
}
