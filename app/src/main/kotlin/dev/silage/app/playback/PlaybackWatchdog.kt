package dev.silage.app.playback

/**
 * Notices a stuck playback engine. A deadlocked mpv blocks every call into it
 * (seen with a system libmpv on NVIDIA): the player freezes, its window keeps
 * the keyboard, and it can't even be closed. The player's update loop calls
 * [alive] about 60 times a second; once it has, [STUCK_MS] of silence is a
 * hang (buffering and seeking don't block that loop). [stuck] reports it once.
 */
class PlaybackWatchdog {
    @Volatile private var lastAlive = -1L
    @Volatile private var reported = false
    @Volatile private var stopped = false
    @Volatile private var lastCheck = -1L

    fun alive(now: Long) { lastAlive = now }

    fun stop() { stopped = true }

    /** Called about once a second; a much longer gap since the previous call means the computer slept. */
    fun stuck(now: Long): Boolean {
        val previousCheck = lastCheck
        lastCheck = now
        if (previousCheck >= 0 && now - previousCheck > SUSPEND_GAP_MS && lastAlive >= 0) {
            lastAlive = now // Woke from sleep: everything was silent, not stuck. Time afresh.
            return false
        }
        val last = lastAlive
        if (stopped || reported || last < 0 || now - last < STUCK_MS) return false
        reported = true
        return true
    }

    companion object {
        const val STUCK_MS = 20_000L
        private const val SUSPEND_GAP_MS = 5_000L
    }
}

/**
 * The stuck engine can't be shut down, so Silage notes why in its log folder,
 * starts itself again (back at Home; the server kept the playback position) and
 * ends this process at once: exit hooks would wait on the stuck mpv.
 */
internal fun restartAfterStuckPlayback() {
    val note = "${java.time.Instant.now()} The playback engine stopped responding for " +
        "${PlaybackWatchdog.STUCK_MS / 1000} s; Silage restarted itself.\n"
    System.err.print("Silage: $note")
    runCatching {
        val log = dev.silage.app.XdgPaths.state.resolve("playback-stuck.log")
        java.nio.file.Files.createDirectories(log.parent)
        java.nio.file.Files.writeString(log, note, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
    runCatching {
        System.getProperty("jpackage.app-path")?.let { app ->
            ProcessBuilder(app).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
    }
    Runtime.getRuntime().halt(3)
}
