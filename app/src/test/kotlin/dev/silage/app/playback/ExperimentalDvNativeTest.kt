package dev.silage.app.playback

import dev.silage.player.MpvPlayer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in hardware test against a running patched KWin; uses no privileged writes. */
class ExperimentalDvNativeTest {
    @Test fun profile5RequestsCompositorMetadataAndRestoresItOnClose() {
        val path = System.getenv("SILAGE_NATIVE_DV_TEST_CLIP")
        assumeTrue(!path.isNullOrBlank(), "Requires the patched compositor, a local sample, and an active HDR output")
        val clip = File(path!!)
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(clip.inputStream(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        assertEquals("dacfd04518accd6367530b650dfeea429227df2be171bd99b4bdad36d31bbf9f",
            digest.digest().joinToString("") { "%02x".format(it) }, "Expected the pinned official Profile 5 sample")
        val reader = System.getenv("SILAGE_NATIVE_DV_KMS_TOOL")
            ?: File("../build/dv-lab/kms-snapshot").canonicalPath
        fun metadata(): String {
            val process = ProcessBuilder(reader, "/dev/dri/card1", "DP-3").start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "KMS snapshot timed out")
                assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
                return Json.parseToJsonElement(process.inputStream.bufferedReader().readText()).jsonObject
                    .getValue("hdr_output_metadata_hex").jsonPrimitive.content.take(60)
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        val original = metadata()
        val expected = "000000000200268a763908217e9bfa194608ee3d3040e8030000fe03aa03"
        assertTrue(original != expected, "Another experimental session already holds the output")
        val player = MpvPlayer(VideoOutput.OwnWindow("DP-3").mpvOptions() + mapOf(
            "config" to "no", "load-scripts" to "no", "ao" to "null", "mute" to "yes",
        ))
        val session = ExperimentalDvSession("DP-3", player.state, player::getProperty)
        try {
            player.load(clip.canonicalPath)
            await("Compositor did not commit the expected HDR metadata", 20_000) { metadata() == expected }
            repeat(5) {
                Thread.sleep(1_000)
                assertTrue(experimentalDvEligible("DP-3", player::getProperty), "Profile 5 renderer became ineligible")
                assertEquals(expected, metadata(), "Compositor did not retain the output profile")
            }
            println("Compositor Profile 5 signal retained; decoder=${player.getProperty("hwdec-current")}, dropped=${player.getProperty("frame-drop-count")}")
            session.close()
            await("Compositor did not restore ordinary HDR after client disconnect", 8_000) { metadata() == original }
            println("Compositor restored the original HDR metadata after the application connection closed")
        } finally {
            session.close()
            player.close()
        }
    }

    private fun await(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue(condition(), message)
    }
}
