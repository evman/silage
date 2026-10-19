package dev.silage.app.playback.hud

import dev.silage.app.playback.PlaybackMeta
import dev.silage.app.playback.VideoOutput
import dev.silage.player.MpvPlayer
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Opt-in GPU smoke test: SILAGE_NATIVE_HUD_TEST_CLIP=/path/to/local/video.
 * SILAGE_NATIVE_HUD_TEST_SCREEN=DP-3 additionally tests fullscreen HDR on that output.
 */
class NativeHudInputTest {
    @Test fun nativeWindowMouseAndKeyboardControlTheHud() {
        val clip = System.getenv("SILAGE_NATIVE_HUD_TEST_CLIP")
        assumeTrue(!clip.isNullOrBlank(), "Requires a local clip and a graphical session")
        val screen = System.getenv("SILAGE_NATIVE_HUD_TEST_SCREEN")
        val player = MpvPlayer(VideoOutput.OwnWindow(screen).mpvOptions() + mapOf(
            "fullscreen" to if (screen == null) "no" else "yes", "geometry" to "960x540", "ao" to "null",
            "pause" to "yes", "loop-file" to "inf",
        ))
        try {
            player.load(clip!!)
            await("Video window did not open") {
                (player.getProperty("osd-width")?.toIntOrNull() ?: 0) > 0 && player.state.value.durationSeconds > 0
            }
            val w = player.getProperty("osd-width")!!.toInt()
            val h = player.getProperty("osd-height")!!.toInt()
            fun mouse(x: Int, y: Int, click: Boolean = false) {
                val coords = arrayOf("mouse", (x * w / 960).toString(), (y * h / 540).toString())
                player.command(*(if (click) coords + arrayOf("0", "single") else coords))
            }
            PlayerHud(player, PlaybackMeta("HDR input smoke test")).use { hud ->
                // Let the HUD appear before sending native input through mpv's bindings.
                Thread.sleep(700)
                player.command("keypress", "SPACE")
                await("Space did not resume playback") { !player.state.value.paused }
                player.command("keypress", "SPACE")
                await("Space did not pause playback") { player.state.value.paused }

                // A fast click must work even if down/up arrive in the same HUD frame.
                mouse(480, 503)
                repeat(200) { player.command("script-message", "silage-mouse") }
                mouse(480, 503, click = true)
                await("Native click did not activate Play", 1_000) { !player.state.value.paused }
                player.command("keypress", "SPACE")
                await("Could not pause after the click") { player.state.value.paused }

                // Real button edges also support dragging the seek bar.
                mouse(250, 465)
                player.command("keydown", "MBTN_LEFT")
                Thread.sleep(100)
                mouse(700, 465)
                player.command("script-message", "silage-mouse")
                Thread.sleep(100)
                player.command("keyup", "MBTN_LEFT")
                await("Native seek-bar drag did not seek") {
                    player.state.value.positionSeconds > player.state.value.durationSeconds * 0.6
                }
                hud.key(HudKey.Info)
                if (screen != null) {
                    assertTrue(player.getProperty("video-target-params/gamma") in listOf("pq", "hlg"), "Expected HDR output")
                    println("Native fullscreen HDR input passed at ${w}x$h on $screen")
                }
                val fullscreen = player.getProperty("fullscreen")
                player.command("keypress", "ESC")
                Thread.sleep(300)
                assertTrue(player.state.value.loaded, "Dismissing a menu must preserve playback")
                assertEquals(fullscreen, player.getProperty("fullscreen"), "Escape must not shrink the native window")
                player.command("keypress", "ESC")
                await("Escape did not leave playback") { !player.state.value.loaded }
                assertEquals(fullscreen, player.getProperty("fullscreen"), "Exit must not first leave fullscreen")
            }
        } finally {
            player.close()
        }
    }

    private fun await(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), message)
    }
}
