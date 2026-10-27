package dev.silage.app.update

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateOfferTest {
    private val keys = UpdateTestKeys()
    private val cacheDir = createTempDirectory("silage-offer")
    private val v = Version(0, 5, 1)

    @AfterTest fun cleanUp() {
        cacheDir.toFile().deleteRecursively()
    }

    private var release: Release? = null
    private val served = mutableMapOf<String, ByteArray>()
    private val commands = mutableListOf<List<String>>()
    private var exits = 0
    private var relaunches = 0
    private val opened = mutableListOf<String>()

    private fun publish(missingDeb: Boolean = false, asset: String = "silage_${v}_amd64.deb") {
        val tag = "v$v"
        val base = "https://github.com/evman/silage/releases/download/$tag"
        val deb = asset to "deb".toByteArray()
        val sums = UpdateTestKeys.sums(deb)
        val files = mapOf(deb, "SHA256SUMS" to sums, "SHA256SUMS.sig" to keys.sign(sums).toByteArray())
        val assets = files.keys.associateWith { "$base/$it" }
        files.forEach { (name, bytes) -> if (!(missingDeb && name == deb.first)) served[assets.getValue(name)] = bytes }
        release = Release(tag, v, "https://github.com/evman/silage/releases/tag/$tag", assets)
    }

    private fun TestScope.offer(
        type: InstallType = InstallType.Deb,
        autoDownload: Boolean = true,
        pkexec: Boolean = true,
        exitCode: Int = 0,
        installed: () -> Version? = { Version(0, 5, 0) },
    ): Pair<Updater, UpdateOffer> {
        val updater = Updater(
            running = Version(0, 5, 0), type = type,
            source = object : ReleaseSource { override suspend fun latest() = release },
            download = { url, to, _ -> to.writeBytes(served[url] ?: error("HTTP 404")) },
            verifier = UpdateVerifier(keys.publicKeyPem), cacheDir = cacheDir, installedVersion = installed,
            autoDownload = { autoDownload }, scope = backgroundScope,
        )
        val applier = UpdateApplier(
            type = type, run = { commands += it; exitCode }, startDetached = { commands += it },
            which = { if (pkexec) Path.of("/usr/bin/$it") else null },
            relaunch = { relaunches++ }, exit = { exits++ },
        )
        return updater to UpdateOffer(updater, applier, browse = { opened += it }, scope = backgroundScope)
    }

    private fun TestScope.check(updater: Updater) {
        updater.checkNow()
        runCurrent()
    }

    @Test fun shownUpdateKeepsTheLastOfferThroughDownloadsAndFailures() {
        val ready = UpdateState.Ready(v, null)
        assertEquals(ready, shownUpdate(null, ready))
        assertEquals(ready, shownUpdate(ready, UpdateState.Downloading(v, 0.3f)))
        assertEquals(ready, shownUpdate(ready, UpdateState.Failed("Couldn't download Silage 0.5.1")))
        assertEquals(ready, shownUpdate(ready, UpdateState.Checking))
        assertNull(shownUpdate(ready, UpdateState.Idle))
        assertNull(shownUpdate(null, UpdateState.Downloading(v, 0.3f)))
        assertEquals(UpdateState.RestartToFinish(v), shownUpdate(ready, UpdateState.RestartToFinish(v)))
    }

    @Test fun readyInstallsTheVerifiedFileAndRestarts() = runTest {
        publish()
        val (updater, offer) = offer()
        check(updater)
        assertIs<UpdateState.Ready>(offer.shown.value)
        assertEquals(offer.shown.value, updater.state.value)
        offer.select()
        runCurrent()
        assertEquals(listOf("pkexec", "apt-get", "install", "-y"), commands.single().take(4))
        assertEquals(1, relaunches)
        assertEquals(1, exits)
        assertNull(offer.notice.value)
    }

    @Test fun readyWithoutAutomaticDownloadsDownloadsFirst() = runTest {
        publish()
        val (updater, offer) = offer(autoDownload = false)
        check(updater)
        assertEquals(UpdateState.Ready(v, null), offer.shown.value)
        offer.select()
        runCurrent()
        assertEquals(cacheDir.resolve("updates/silage_${v}_amd64.deb").toString(), commands.single().last())
        assertEquals(1, exits)
    }

    @Test fun aFailedDownloadShowsANoticeAndKeepsTheEntry() = runTest {
        publish(missingDeb = true)
        val (updater, offer) = offer(autoDownload = false)
        check(updater)
        offer.select()
        runCurrent()
        assertIs<UpdateState.Failed>(updater.state.value)
        assertEquals(UpdateState.Ready(v, null), offer.shown.value)
        assertEquals("Couldn't download Silage 0.5.1. Try again later.", offer.notice.value?.message)
        assertEquals(0, exits)
        offer.dismissNotice()
        assertNull(offer.notice.value)
    }

    // Linux package-manager commands; Windows paths would be quoted for their backslashes.
    @EnabledOnOs(OS.LINUX)
    @Test fun withoutPkexecTheNoticeShowsTheCommand() = runTest {
        publish()
        val (updater, offer) = offer(pkexec = false)
        check(updater)
        offer.select()
        runCurrent()
        val command = offer.notice.value?.command
        assertEquals("sudo apt-get install -y ${cacheDir.resolve("updates/silage_${v}_amd64.deb")}", command)
        assertEquals(0, exits)
    }

    @Test fun aFailedInstallShowsItsMessage() = runTest {
        publish()
        val (updater, offer) = offer(exitCode = 1)
        check(updater)
        offer.select()
        runCurrent()
        assertEquals("The install failed (exit code 1).", offer.notice.value?.message)
        assertNull(offer.notice.value?.command)
    }

    @Test fun restartToFinishOnlyRelaunches() = runTest {
        publish()
        val (updater, offer) = offer(installed = { v })
        check(updater)
        assertEquals(UpdateState.RestartToFinish(v), offer.shown.value)
        offer.select()
        runCurrent()
        assertEquals(emptyList(), commands)
        assertEquals(1, relaunches)
        assertEquals(1, exits)
    }

    private val windows = InstallType.WindowsInstaller(Path.of("C:/Users/me/AppData/Local/Programs/Silage"))

    @Test fun quittingWithAReadyUpdateStartsTheVerifiedInstaller() = runTest {
        publish(asset = "Silage-$v-Setup.exe")
        val (updater, offer) = offer(type = windows)
        check(updater)
        assertIs<UpdateState.Ready>(updater.state.value)
        offer.onExit()
        assertEquals(cacheDir.resolve("updates/Silage-$v-Setup.exe").toString(), commands.single().first())
    }

    @Test fun quittingNeverStartsATamperedInstaller() = runTest {
        publish(asset = "Silage-$v-Setup.exe")
        val (updater, offer) = offer(type = windows)
        check(updater)
        val file = assertIs<UpdateState.Ready>(updater.state.value).file!!
        file.writeBytes("tampered".toByteArray())
        offer.onExit()
        assertEquals(emptyList(), commands)
    }

    @Test fun availableOpensTheReleasePage() = runTest {
        publish()
        val (updater, offer) = offer(type = InstallType.Tarball(cacheDir, writable = false))
        check(updater)
        offer.select()
        runCurrent()
        assertEquals(listOf("https://github.com/evman/silage/releases/tag/v0.5.1"), opened)
        assertEquals(0, exits)
    }
}
