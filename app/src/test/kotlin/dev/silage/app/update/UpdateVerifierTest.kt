package dev.silage.app.update

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateVerifierTest {
    private val keys = UpdateTestKeys()
    private val verifier = UpdateVerifier(keys.publicKeyPem)
    private val dir = createTempDirectory("silage-verify")
    private val name = "silage_0.5.1_amd64.deb"
    private val content = "the package".toByteArray()
    private val sums = UpdateTestKeys.sums(
        "silage-0.5.1-linux-x86_64.tar.gz" to "the tarball".toByteArray(),
        name to content,
    )

    @AfterTest fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun file(bytes: ByteArray = content): Path = dir.resolve(name).also { it.writeBytes(bytes) }

    @Test fun acceptsAGoodSignatureAndHash() {
        assertTrue(verifier.verify(sums, keys.sign(sums), name, file()))
        // The signature file may end with a newline.
        assertTrue(verifier.verify(sums, keys.sign(sums) + "\n", name, file()))
    }

    @Test fun rejectsATamperedFile() {
        assertFalse(verifier.verify(sums, keys.sign(sums), name, file("the packagf".toByteArray())))
    }

    @Test fun rejectsATamperedSumsFile() {
        val signature = keys.sign(sums)
        val tampered = UpdateTestKeys.sums(name to "something else".toByteArray())
        assertFalse(verifier.verify(tampered, signature, name, file("something else".toByteArray())))
    }

    @Test fun rejectsAFileMissingFromSums() {
        assertFalse(verifier.verify(sums, keys.sign(sums), "silage-0.5.1-1.x86_64.rpm", file()))
        // A name that is only a suffix of a listed one is not listed.
        assertFalse(verifier.verify(sums, keys.sign(sums), "amd64.deb", file()))
    }

    @Test fun rejectsABadSignature() {
        val other = UpdateTestKeys()
        assertFalse(verifier.verify(sums, other.sign(sums), name, file()))
        assertFalse(verifier.verify(sums, "not base64!", name, file()))
        assertFalse(verifier.verify(sums, "", name, file()))
    }

    @Test fun theBundledKeyLoads() {
        UpdateVerifier.bundled()
    }
}
