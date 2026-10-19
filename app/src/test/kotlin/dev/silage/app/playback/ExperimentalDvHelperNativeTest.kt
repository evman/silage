package dev.silage.app.playback

import dev.silage.player.MpvPlayer
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit hardware opt-in; exercises the installed root-owned helper on stock KWin. */
class ExperimentalDvHelperNativeTest {
    @Test fun dolbyVisionSignalOnStockDesktopAndRestorationAfterClientClose() {
        val path = System.getenv("SILAGE_DV_HELPER_TEST_CLIP")
        val savedPlan = System.getenv("SILAGE_DV_HELPER_TEST_SAVED_PLAN")
        assumeTrue(!path.isNullOrBlank() || !savedPlan.isNullOrBlank(), "Requires an installed helper, sudo authorization and active 4K HDR output")
        val clip = path?.let(::File)
        val hash = clip?.let {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(it.inputStream(), digest).use { stream -> stream.transferTo(OutputStream.nullOutputStream()) }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        var headers = emptyMap<String, String>()
        val source = if (!savedPlan.isNullOrBlank()) {
            val text = File(savedPlan).readText()
            val plan = Json.parseToJsonElement(text.substring(text.indexOf('{'))).jsonObject
                .getValue("playback_plan").jsonObject
            val stream = plan.getValue("stream").jsonObject
            headers = stream["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            stream.getValue("url").jsonPrimitive.content
        } else clip!!.canonicalPath
        val expectedProfile = mapOf(
            "dacfd04518accd6367530b650dfeea429227df2be171bd99b4bdad36d31bbf9f" to "5",
            "d054a818de665bc3737a4c5b1662262929ed777b102ea3539afb050fcb4d2cae" to "8",
        )[hash] ?: if (!savedPlan.isNullOrBlank() || (hash != null && hash == System.getenv("SILAGE_DV_HELPER_TEST_PROFILE7_SHA256"))) "7"
            else error("Expected a pinned official Dolby sample or an explicitly hashed private Profile 7 excerpt")

        fun probe(): Pair<Boolean, Boolean> {
            val builder = ProcessBuilder("/usr/bin/sudo", "-n", DvHelperExecutable, "--probe")
            builder.environment().remove("LD_PRELOAD")
            val process = builder.start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Read-only signal probe timed out")
                assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
                val fields = Json.parseToJsonElement(process.inputStream.bufferedReader().readText()).jsonObject
                return (fields.getValue("ordinary_hdr").jsonPrimitive.content == "true") to
                    (fields.getValue("candidate_programmed").jsonPrimitive.content == "true")
            } finally { if (process.isAlive) process.destroyForcibly() }
        }

        assertEquals(true to false, probe(), "Another experiment must not hold the output")
        val player = MpvPlayer(VideoOutput.OwnWindow("DP-3").mpvOptions() + mapOf(
            "config" to "no", "load-scripts" to "no", "ao" to "null", "mute" to "yes",
            "loop-file" to if (expectedProfile == "7") "inf" else "no",
        ))
        val owner = DvHelperAuthorization(connect = {
            val builder = ProcessBuilder("/usr/bin/sudo", "-n", DvHelperExecutable)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
            builder.environment().remove("LD_PRELOAD")
            DvHelperProcess(builder.start())
        })
        val session = ExperimentalDvHelper("DP-3", player.state, player::getProperty, authorization = owner)
        try {
            player.load(source, headers, startSeconds = if (savedPlan.isNullOrBlank()) null else 120.0)
            await("Helper did not activate", 20_000) { session.status.value == DvHelperStatus.Active }
            repeat(5) {
                Thread.sleep(1_000)
                assertEquals(expectedProfile, player.getProperty("current-tracks/video/dolby-vision-profile"))
                assertTrue(experimentalDvEligible("DP-3", player::getProperty, DvHelperProfiles))
                assertEquals("Signal active (experimental)", session.description())
                assertEquals(false to true, probe(), "Stock desktop overwrote the signal")
            }
            println("Stock-KWin helper Profile $expectedProfile playback; runtime=${player.getProperty("mpv-version")}, decoder=${player.getProperty("hwdec-current")}, dropped=${player.getProperty("frame-drop-count")}")
            if (expectedProfile == "7") {
                val residual = player.getProperty("video-out-params/dolby-vision-residual")
                val mel = System.getenv("SILAGE_DV_HELPER_TEST_RESIDUAL") == "not-required"
                assertEquals(if (mel) "not-required" else "required", residual)
                if (mel) assertTrue(experimentalDvRenderPasses(player::getProperty).none { "enhancement layer" in it })
                else assertTrue(experimentalDvRenderPasses(player::getProperty).any { "enhancement layer" in it })
                val control = if (mel) "dolbyvision=no" else "enhancement-layer=no"
                player.command("vf", "add", "@silage_test_el:format=$control")
                await("Disabling enhancement processing did not release the signal", 8_000) {
                    session.status.value == DvHelperStatus.Idle && probe() == (true to false)
                }
                assertEquals("Profile 7 enhancement layer not active · using HDR10", session.description())
                player.command("vf", "remove", "@silage_test_el")
                await("Reenabling enhancement processing did not reacquire the signal", 8_000) {
                    session.status.value == DvHelperStatus.Active && probe() == (false to true)
                }
                println("Profile 7 ${if (mel) "MEL metadata" else "FEL enhancement layer"} signal follows actual processing (on/off/on)")
            }
            session.close()
            await("Helper did not restore ordinary HDR", 8_000) { probe() == (true to false) }
            assertEquals(DvHelperStatus.Idle, owner.status.value)
            ExperimentalDvHelper("DP-3", player.state, player::getProperty, authorization = owner).use { next ->
                await("App authorization was not reused", 8_000) { next.status.value == DvHelperStatus.Active }
                assertEquals(false to true, probe())
            }
            await("Reused authorization did not restore ordinary HDR", 8_000) { probe() == (true to false) }
            println("Helper restored ordinary HDR between players and reused app authorization")
        } finally {
            session.close()
            owner.close()
            player.close()
        }
    }

    private fun await(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue(condition(), message)
    }
}
