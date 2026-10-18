package dev.silage.app

import java.nio.file.Files
import kotlin.test.*

class SecretRedactionTest {
    @Test fun crashLogAndConsoleNeverEchoAuthenticationOrSignedUrls() {
        val file = Files.createTempDirectory("silage-redaction").resolve("crash.log")
        val exception = IllegalStateException("GET https://fake.example/play?st=DUMMY_SIGNED&sig=DUMMY_SIGNATURE Authorization: Bearer DUMMY_ACCESS {\"password\":\"DUMMY_PASSWORD\",\"refresh_token\":\"DUMMY_REFRESH\"}")
        val captured = java.io.ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(java.io.PrintStream(captured))
            CrashLog(file).record("fake", exception)
        } finally { System.setErr(previous) }
        val text = Files.readString(file) + captured.toString(Charsets.UTF_8)
        for (secret in listOf("DUMMY_SIGNED", "DUMMY_SIGNATURE", "DUMMY_ACCESS", "DUMMY_PASSWORD", "DUMMY_REFRESH")) {
            assertFalse(text.contains(secret), "a dummy secret leaked")
        }
        assertTrue(text.contains("IllegalStateException"))
    }
}
