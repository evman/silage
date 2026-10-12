package dev.silage.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class CrashLogTest {
    @Test fun recordsTheExceptionWithItsStackTrace() {
        val file = Files.createTempDirectory("silage-log").resolve("silage.log")
        val log = CrashLog(file)
        log.record("ui", IllegalStateException("ComposeScene is already closed"))
        log.record("ui", RuntimeException("second"))
        val text = Files.readString(file)
        assertTrue("IllegalStateException: ComposeScene is already closed" in text, text)
        assertTrue("at dev.silage.app.CrashLogTest" in text, "no stack trace: $text")
        assertTrue("second" in text, "should append")
    }

    @Test fun anUnwritableLogNeverThrows() {
        CrashLog(java.nio.file.Path.of("/proc/silage-cannot-write/silage.log")).record("ui", RuntimeException("x"))
    }
}
