package dev.silage.app

/**
 * Startup timings for diagnosing slow launches: set SILAGE_STARTUP_TRACE to a file path and each
 * milestone is appended with milliseconds since the process started. With SILAGE_STARTUP_TRACE_EXIT
 * set too, Silage quits once Home's rows are on screen. Off (and free) otherwise.
 */
object StartupTrace {
    private val file = System.getenv("SILAGE_STARTUP_TRACE")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
    private val exitWhenHome = System.getenv("SILAGE_STARTUP_TRACE_EXIT") != null
    private val processStart = ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(System.currentTimeMillis())
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())

    val enabled: Boolean get() = file != null

    /** Records [name] the first time it is reached. */
    fun mark(name: String) {
        val f = file ?: return
        if (!seen.add(name)) return
        val line = "%6d ms  %s%n".format(System.currentTimeMillis() - processStart, name)
        runCatching { f.appendText(line) }
        if ((name == "home rows shown" || name == "profiles shown") && exitWhenHome) {
            Thread({ Thread.sleep(500); Runtime.getRuntime().halt(0) }, "startup-trace-exit").apply { isDaemon = true; start() }
        }
    }
}
