package dev.silage.app.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A deadlocked mpv (seen with a system libmpv on NVIDIA) blocks every call into
 * it, freezing the player with no way out. The player's update loop checks in
 * ~60 times a second; silence for [PlaybackWatchdog.STUCK_MS] once it has run
 * means the engine is stuck.
 */
class PlaybackWatchdogTest {
    @Test fun neverFiresBeforeThePlayerLoopHasRun() {
        val dog = PlaybackWatchdog()
        assertFalse(dog.stuck(now = 10 * PlaybackWatchdog.STUCK_MS), "starting a slow stream isn't a hang")
    }

    @Test fun firesOnceAfterTheLoopGoesSilent() {
        val dog = PlaybackWatchdog()
        dog.alive(now = 1_000)
        assertFalse(dog.stuck(now = 1_000 + PlaybackWatchdog.STUCK_MS - 1))
        assertTrue(dog.stuck(now = 1_000 + PlaybackWatchdog.STUCK_MS))
        assertFalse(dog.stuck(now = 1_000 + 3 * PlaybackWatchdog.STUCK_MS), "reported once")
    }

    @Test fun checkingInKeepsItQuiet() {
        val dog = PlaybackWatchdog()
        var now = 0L
        repeat(1_000) { now += 1_000; dog.alive(now); assertFalse(dog.stuck(now + 999)) }
    }

    /** Sleep/suspend silences everything; on waking, the clock jump must not read as a hang. */
    @Test fun aSuspendedComputerIsNotAHang() {
        val dog = PlaybackWatchdog()
        dog.alive(now = 0)
        assertFalse(dog.stuck(now = 1_000))
        // The checker itself was away for an hour: the computer slept.
        assertFalse(dog.stuck(now = 3_600_000))
        assertFalse(dog.stuck(now = 3_601_000), "timing restarts after waking")
        // The checker runs every second: a real hang after waking still counts.
        val fired = (2..20).map { s -> dog.stuck(now = 3_600_000L + s * 1_000) }
        assertEquals(listOf(true), fired.drop(18), "fires at 20 s of silence")
        assertFalse(fired.dropLast(1).any { it })
    }

    @Test fun stoppedPlaybackIsNeverStuck() {
        val dog = PlaybackWatchdog()
        dog.alive(now = 0)
        dog.stop()
        assertFalse(dog.stuck(now = 10 * PlaybackWatchdog.STUCK_MS))
    }

    @Test fun twentySecondsOfSilence() = assertEquals(20_000L, PlaybackWatchdog.STUCK_MS)
}
