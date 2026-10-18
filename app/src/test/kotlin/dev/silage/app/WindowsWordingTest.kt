package dev.silage.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WindowsWordingTest {
    @Test fun connectionAndAboutDoNotClaimLinuxOnly() {
        val app = if (Files.exists(Path.of("src/main/kotlin"))) Path.of(".") else Path.of("app")
        for (file in listOf("connect/ConnectScreen.kt", "settings/SettingsScreen.kt")) {
            val source = Files.readString(app.resolve("src/main/kotlin/dev/silage/app/$file"))
            assertFalse(source.contains("client for Linux"), file)
        }
    }
}
