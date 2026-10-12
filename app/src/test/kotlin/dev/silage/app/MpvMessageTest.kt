package dev.silage.app

import dev.silage.player.MpvStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvMessageTest {
    @Test fun okIsOneLine() {
        assertNull(mpvProblem(MpvStatus.Ok("mpv 0.41.0")))
        assertEquals("mpv OK: mpv 0.41.0", mpvCheckLine(MpvStatus.Ok("mpv 0.41.0")))
    }

    @Test fun missingNamesTheInstallCommand() {
        val p = mpvProblem(MpvStatus.Missing("libmpv not found", "sudo apt install libmpv2"))!!
        assertEquals("Silage needs mpv", p.title)
        assertEquals("Silage plays video with mpv 0.37 or newer, which isn't installed.", p.body)
        assertEquals("sudo apt install libmpv2", p.command)
    }

    @Test fun tooOldSaysWhatWasFound() {
        val p = mpvProblem(MpvStatus.TooOld("mpv 0.34.1", "sudo dnf install mpv-libs"))!!
        assertEquals("Your mpv is too old", p.title)
        assertEquals("Silage needs mpv 0.37 or newer; this system has mpv 0.34.1.", p.body)
        assertEquals("sudo dnf install mpv-libs", p.command)
    }

    @Test fun checkLineForProblemsIncludesTheFix() {
        assertEquals(
            "Silage plays video with mpv 0.37 or newer, which isn't installed.\nInstall it with: sudo apt install libmpv2",
            mpvCheckLine(MpvStatus.Missing("x", "sudo apt install libmpv2")),
        )
    }
}
