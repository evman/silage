package dev.silage.app.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import dev.silage.app.ui.UiPrefs
import dev.silage.app.update.InstallType
import dev.silage.app.update.Release
import dev.silage.app.update.ReleaseSource
import dev.silage.app.update.UpdateState
import dev.silage.app.update.UpdateVerifier
import dev.silage.app.update.Updater
import dev.silage.app.update.UpdateTestKeys
import dev.silage.app.update.Version
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalTestApi::class)
class AboutUpdatesTest {
    private val dir = Files.createTempDirectory("silage-about")
    private val prefs = UiPrefs(dir.resolve("ui.json"))
    private var checks = 0
    private var release: Release? = null

    private fun updater() = Updater(
        running = Version(0, 5, 0), type = InstallType.Deb,
        source = object : ReleaseSource { override suspend fun latest(): Release? { checks++; return release } },
        download = { _, _, _ -> error("no downloads here") },
        verifier = UpdateVerifier(UpdateTestKeys().publicKeyPem), cacheDir = dir, installedVersion = { null },
        autoDownload = { false }, scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test fun switchPersists() = runComposeUiTest {
        setContent { MaterialTheme { AboutUpdates(prefs, updater(), openUrl = {}) } }
        assertEquals(true, prefs.automaticUpdates)
        onNodeWithText("Off").performClick()
        waitForIdle()
        assertEquals(false, UiPrefs(dir.resolve("ui.json")).automaticUpdates)
        onNodeWithText("On").performClick()
        waitForIdle()
        assertEquals(true, UiPrefs(dir.resolve("ui.json")).automaticUpdates)
    }

    @Test fun checkNowCallsTheUpdater() = runComposeUiTest {
        val opened = mutableListOf<String>()
        release = Release("v0.5.1", Version(0, 5, 1), "https://github.com/evman/silage/releases/tag/v0.5.1", emptyMap())
        setContent { MaterialTheme { AboutUpdates(prefs, updater(), openUrl = { opened += it }) } }
        onNodeWithText("Up to date").assertIsDisplayed()
        onAllNodesWithText("What's new").assertCountEquals(0)
        onNodeWithText("Check now").performClick()
        waitForIdle()
        assertEquals(1, checks)
        onNodeWithText("Silage 0.5.1 is ready").assertIsDisplayed()
        onNodeWithText("What's new").performClick()
        assertEquals(listOf("https://github.com/evman/silage/releases/tag/v0.5.1"), opened)
    }

    @Test fun statusTextFollowsState() {
        val v = Version(0, 5, 1)
        assertEquals("Up to date", updateStatusText(UpdateState.Idle))
        assertEquals("Checking for updates…", updateStatusText(UpdateState.Checking))
        assertEquals("Downloading 0.5.1… 42%", updateStatusText(UpdateState.Downloading(v, 0.42f)))
        assertEquals("Silage 0.5.1 is ready", updateStatusText(UpdateState.Ready(v, Path.of("/tmp/x"))))
        assertEquals("Silage 0.5.1 is ready", updateStatusText(UpdateState.Ready(v, null)))
        assertEquals("Silage 0.5.1 is ready", updateStatusText(UpdateState.RestartToFinish(v)))
        assertEquals("Silage 0.5.1 is available", updateStatusText(UpdateState.Available(v, "https://example.org")))
        assertEquals("Couldn't check for updates", updateStatusText(UpdateState.Failed("Couldn't check for updates")))
        assertEquals("Couldn't download Silage 0.5.1", updateStatusText(UpdateState.Failed("Couldn't download Silage 0.5.1")))
    }
}
