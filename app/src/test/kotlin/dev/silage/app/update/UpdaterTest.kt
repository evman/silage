package dev.silage.app.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterTest {
    private val keys = UpdateTestKeys()
    private val verifier = UpdateVerifier(keys.publicKeyPem)
    private val cacheDir = createTempDirectory("silage-updater")
    private val updatesDir = cacheDir.resolve("updates")

    private class FakeSource : ReleaseSource {
        var release: Release? = null
        var calls = 0
        override suspend fun latest(): Release? {
            calls++
            return release
        }
    }

    private val source = FakeSource()
    private val served = mutableMapOf<String, ByteArray>()
    private val requested = mutableListOf<String>()
    private val download: suspend (String, Path, (Float) -> Unit) -> Unit = { url, to, progress ->
        requested += url
        val bytes = served[url] ?: error("HTTP 404 for $url")
        to.writeBytes(bytes)
        progress(0.5f)
        progress(1f)
    }

    @AfterTest fun cleanUp() {
        cacheDir.toFile().deleteRecursively()
    }

    private fun downloadBase(tag: String) = "https://github.com/evman/silage/releases/download/$tag"

    /** Publishes a signed release [v] with every install type's asset; returns the .deb's bytes. */
    private fun publish(v: String, tamperDeb: Boolean = false): ByteArray {
        val tag = "v$v"
        val files = listOf(
            "silage_${v}_amd64.deb", "silage-$v-1.x86_64.rpm", "silage-bin-$v-1-x86_64.pkg.tar.zst",
            "silage-$v-linux-x86_64.tar.gz", "Silage-$v-Setup.exe",
        ).associateWith { "$it contents".toByteArray() }
        val sums = UpdateTestKeys.sums(*files.toList().toTypedArray())
        val all = files + mapOf("SHA256SUMS" to sums, "SHA256SUMS.sig" to keys.sign(sums).toByteArray())
        val assets = all.keys.associateWith { "${downloadBase(tag)}/$it" }
        all.forEach { (name, bytes) -> served[assets.getValue(name)] = bytes }
        if (tamperDeb) served[assets.getValue("silage_${v}_amd64.deb")] = "tampered".toByteArray()
        source.release = Release(tag, Version.parse(v)!!, "https://github.com/evman/silage/releases/tag/$tag", assets)
        return files.getValue("silage_${v}_amd64.deb")
    }

    private fun TestScope.updater(
        running: String = "0.5.0",
        type: InstallType = InstallType.Deb,
        installed: () -> Version? = { Version.parse(running) },
        autoDownload: () -> Boolean = { true },
        intervalMs: Long = 6 * 3_600_000L,
    ) = Updater(
        running = Version.parse(running), type = type, source = source, download = download, verifier = verifier,
        cacheDir = cacheDir, installedVersion = installed, autoDownload = autoDownload, scope = backgroundScope,
        intervalMs = intervalMs,
    )

    private fun TestScope.check(updater: Updater) {
        updater.checkNow()
        runCurrent()
    }

    @Test fun notNewerStaysIdle() = runTest {
        val updater = updater()
        publish("0.5.0")
        check(updater)
        assertEquals(UpdateState.Idle, updater.state.value)
        publish("0.4.1")
        check(updater)
        assertEquals(UpdateState.Idle, updater.state.value)
        assertEquals(2, source.calls)
        assertEquals(emptyList(), requested)
    }

    @Test fun newerDownloadsVerifiesAndIsReady() = runTest {
        val updater = updater()
        val states = mutableListOf<UpdateState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { updater.state.toList(states) }
        val deb = publish("0.5.1")
        check(updater)
        val file = updatesDir.resolve("silage_0.5.1_amd64.deb")
        assertEquals(UpdateState.Ready(Version(0, 5, 1), file), updater.state.value)
        assertContentEquals(deb, file.readBytes())
        assertEquals(listOf(file), updatesDir.listDirectoryEntries())
        assertEquals(
            listOf(
                UpdateState.Idle, UpdateState.Checking,
                UpdateState.Downloading(Version(0, 5, 1), 0f),
                UpdateState.Downloading(Version(0, 5, 1), 0.5f),
                UpdateState.Downloading(Version(0, 5, 1), 1f),
                UpdateState.Ready(Version(0, 5, 1), file),
            ),
            states,
        )
        assertEquals(file, updater.fetchPending())
        // Checking again with the same release changes nothing and downloads nothing more.
        val downloads = requested.size
        check(updater)
        assertEquals(UpdateState.Ready(Version(0, 5, 1), file), updater.state.value)
        assertEquals(downloads, requested.size)
    }

    @Test fun latestIsTheNewestReleaseFound() = runTest {
        val updater = updater()
        assertEquals(null, updater.latest.value)
        publish("0.5.0")
        check(updater)
        assertEquals(Version(0, 5, 0), updater.latest.value?.version)
        publish("0.5.1")
        check(updater)
        assertEquals("https://github.com/evman/silage/releases/tag/v0.5.1", updater.latest.value?.notesUrl)
        // An unanswered check keeps the last release found.
        source.release = null
        check(updater)
        assertEquals(Version(0, 5, 1), updater.latest.value?.version)
    }

    @Test fun eachInstallTypeGetsItsOwnAsset() = runTest {
        val windows = Path.of("C:/Users/me/AppData/Local/Programs/Silage")
        val expected = mapOf(
            InstallType.Rpm to "silage-0.5.1-1.x86_64.rpm",
            InstallType.Pacman to "silage-bin-0.5.1-1-x86_64.pkg.tar.zst",
            InstallType.Tarball(cacheDir, writable = true) to "silage-0.5.1-linux-x86_64.tar.gz",
            InstallType.WindowsInstaller(windows) to "Silage-0.5.1-Setup.exe",
        )
        for ((type, name) in expected) {
            publish("0.5.1")
            val updater = updater(type = type)
            check(updater)
            assertEquals(UpdateState.Ready(Version(0, 5, 1), updatesDir.resolve(name)), updater.state.value, "$type")
        }
    }

    @Test fun aCachedFileChangedAfterVerificationIsNotHandedOut() = runTest {
        val updater = updater()
        val deb = publish("0.5.1")
        check(updater)
        val file = updatesDir.resolve("silage_0.5.1_amd64.deb")
        assertEquals(UpdateState.Ready(Version(0, 5, 1), file), updater.state.value)

        // Replaced in the cache after it was verified: it is downloaded and verified again.
        file.writeBytes("replaced".toByteArray())
        requested.clear()
        assertEquals(file, updater.fetchPending())
        assertContentEquals(deb, file.readBytes())
        assertTrue(requested.isNotEmpty())
        assertEquals(UpdateState.Ready(Version(0, 5, 1), file), updater.state.value)

        // And when the fresh download can't be had, nothing is handed out or kept.
        file.writeBytes("replaced".toByteArray())
        served.remove(source.release!!.assets.getValue("silage_0.5.1_amd64.deb"))
        assertEquals(null, updater.fetchPending())
        assertIs<UpdateState.Failed>(updater.state.value)
        assertEquals(emptyList(), updatesDir.listDirectoryEntries())
    }

    @Test fun failedVerificationDeletesTheFileAndIsNotReady() = runTest {
        val updater = updater()
        publish("0.5.1", tamperDeb = true)
        check(updater)
        assertIs<UpdateState.Failed>(updater.state.value)
        assertEquals(emptyList(), updatesDir.listDirectoryEntries())
        assertEquals(null, updater.fetchPending())
    }

    @Test fun downloadsComeFromTheTaggedRelease() = runTest {
        val updater = updater()
        publish("0.5.1")
        check(updater)
        assertIs<UpdateState.Ready>(updater.state.value)
        val base = downloadBase("v0.5.1") + "/"
        assertEquals(
            setOf("${base}SHA256SUMS", "${base}SHA256SUMS.sig", "${base}silage_0.5.1_amd64.deb"),
            requested.toSet(),
        )
        assertTrue(requested.none { "latest" in it }, requested.toString())

        // A release whose links point anywhere but its own tag is not used at all.
        val release = source.release!!
        source.release = release.copy(
            tag = "v0.5.2", version = Version(0, 5, 2),
            assets = release.assets.mapValues { (name, _) -> "https://github.com/evman/silage/releases/latest/download/$name" },
        )
        requested.clear()
        check(updater)
        assertEquals(emptyList(), requested)
        assertIs<UpdateState.Failed>(updater.state.value)
    }

    @Test fun autoDownloadOffIsReadyWithoutAFile() = runTest {
        val updater = updater(autoDownload = { false })
        val deb = publish("0.5.1")
        check(updater)
        assertEquals(UpdateState.Ready(Version(0, 5, 1), null), updater.state.value)
        assertEquals(emptyList(), requested)

        val file = updater.fetchPending()
        assertNotNull(file)
        assertContentEquals(deb, file.readBytes())
        assertEquals(UpdateState.Ready(Version(0, 5, 1), file), updater.state.value)
        assertTrue(requested.all { it.startsWith(downloadBase("v0.5.1")) })
    }

    @Test fun systemUpdatedUnderneathOnlyOffersRestart() = runTest {
        val updater = updater(installed = { Version(0, 5, 1) })
        publish("0.5.2")
        check(updater)
        assertEquals(UpdateState.RestartToFinish(Version(0, 5, 1)), updater.state.value)
        assertEquals(0, source.calls)
        assertEquals(emptyList(), requested)
        assertEquals(null, updater.fetchPending())
    }

    @Test fun readOnlyTarballIsOnlyAvailable() = runTest {
        val updater = updater(type = InstallType.Tarball(Path.of("/usr/local/silage"), writable = false))
        publish("0.5.1")
        check(updater)
        assertEquals(
            UpdateState.Available(Version(0, 5, 1), "https://github.com/evman/silage/releases/tag/v0.5.1"),
            updater.state.value,
        )
        assertEquals(emptyList(), requested)
        assertEquals(null, updater.fetchPending())
    }

    @Test fun developmentNeverChecks() = runTest {
        var askedInstalled = false
        val updater = updater(running = "0.5.0", type = InstallType.Development, installed = { askedInstalled = true; null })
        publish("0.5.1")
        updater.start()
        updater.checkNow()
        advanceTimeBy(7 * 24 * 3_600_000L)
        runCurrent()
        assertEquals(UpdateState.Idle, updater.state.value)
        assertEquals(0, source.calls)
        assertFalse(askedInstalled)
    }

    @Test fun aNewerReleaseReplacesAPendingOne() = runTest {
        val updater = updater()
        publish("0.5.1")
        check(updater)
        val first = updatesDir.resolve("silage_0.5.1_amd64.deb")
        assertEquals(UpdateState.Ready(Version(0, 5, 1), first), updater.state.value)

        val deb = publish("0.5.2")
        check(updater)
        val second = updatesDir.resolve("silage_0.5.2_amd64.deb")
        assertEquals(UpdateState.Ready(Version(0, 5, 2), second), updater.state.value)
        assertContentEquals(deb, second.readBytes())
        assertFalse(first.exists())
        assertEquals(listOf(second), updatesDir.listDirectoryEntries())
    }

    @Test fun aNewerReleaseReplacesAPendingOneWithoutAFile() = runTest {
        val updater = updater(autoDownload = { false })
        publish("0.5.1")
        check(updater)
        publish("0.5.2")
        check(updater)
        assertEquals(UpdateState.Ready(Version(0, 5, 2), null), updater.state.value)
        assertEquals(updatesDir.resolve("silage_0.5.2_amd64.deb"), updater.fetchPending())
    }

    @Test fun unreachableGitHubKeepsAPendingUpdateAndOtherwiseFailsQuietly() = runTest {
        val updater = updater()
        check(updater)
        assertIs<UpdateState.Failed>(updater.state.value)

        publish("0.5.1")
        check(updater)
        val ready = updater.state.value
        assertIs<UpdateState.Ready>(ready)
        source.release = null
        check(updater)
        assertEquals(ready, updater.state.value)
    }

    @Test fun aFailedDownloadIsRetriedOnTheNextCheck() = runTest {
        val updater = updater()
        publish("0.5.1")
        val debUrl = source.release!!.assets.getValue("silage_0.5.1_amd64.deb")
        val deb = served.remove(debUrl)!!
        check(updater)
        assertIs<UpdateState.Failed>(updater.state.value)
        assertEquals(emptyList(), updatesDir.listDirectoryEntries())
        served[debUrl] = deb
        check(updater)
        assertIs<UpdateState.Ready>(updater.state.value)
    }

    @Test fun checksRepeatEveryInterval() = runTest {
        val updater = updater(intervalMs = 60_000L)
        publish("0.5.0")
        updater.start()
        updater.start() // A second start does not double the checks.
        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(0, source.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, source.calls)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, source.calls)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, source.calls)
        assertEquals(UpdateState.Idle, updater.state.value)
        assertTrue(Files.notExists(updatesDir) || updatesDir.listDirectoryEntries().isEmpty())
    }
}
