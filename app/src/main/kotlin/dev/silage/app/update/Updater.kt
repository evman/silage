package dev.silage.app.update

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val version: Version, val fraction: Float) : UpdateState

    /** Verified and ready to apply; [file] is null when automatic downloads are off (see [Updater.fetchPending]). */
    data class Ready(val version: Version, val file: Path?) : UpdateState

    /** The installed copy was already updated (by the system package manager): only a restart is needed. */
    data class RestartToFinish(val version: Version) : UpdateState

    /** Silage can't update this copy (a read-only tarball): the release page is all it offers. */
    data class Available(val version: Version, val pageUrl: String) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Looks for a newer release than [running] and, for install types that update themselves,
 * downloads the [type]'s asset from that release's tag and verifies it against the same tag's
 * signed `SHA256SUMS` before offering it. Files go in `<cacheDir>/updates`.
 *
 * Blocking file work (hashing the download) runs on [scope]'s dispatcher, so give it an IO scope.
 */
class Updater(
    private val running: Version?,
    private val type: InstallType,
    private val source: ReleaseSource,
    private val download: suspend (url: String, to: Path, progress: (Float) -> Unit) -> Unit,
    private val verifier: UpdateVerifier,
    cacheDir: Path,
    private val installedVersion: () -> Version?,
    private val autoDownload: () -> Boolean,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 6 * 3_600_000L,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val updatesDir = cacheDir.resolve("updates")
    private val work: CoroutineContext = scope.coroutineContext.minusKey(Job)
    private val lock = Mutex()
    private var pending: Release? = null
    private var started = false

    private val updates get() = type !is InstallType.Development

    /** Checks [FIRST_CHECK_DELAY_MS] after starting, then every [intervalMs]; never for a development build. */
    fun start() {
        if (!updates || started) return
        started = true
        scope.launch {
            delay(FIRST_CHECK_DELAY_MS)
            while (true) {
                check()
                delay(intervalMs)
            }
        }
    }

    fun checkNow() {
        if (!updates) return
        scope.launch { check() }
    }

    /**
     * The verified file of a [UpdateState.Ready] update, downloading it first when automatic
     * downloads were off; null when there is nothing to apply or the download failed.
     */
    suspend fun fetchPending(): Path? = withContext(work) {
        lock.withLock {
            val ready = _state.value as? UpdateState.Ready ?: return@withLock null
            ready.file?.takeIf { Files.isRegularFile(it) } ?: pending?.let { fetch(it) }
        }
    }

    private suspend fun check() = lock.withLock {
        try {
            checkLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!offering()) _state.value = UpdateState.Failed(COULD_NOT_CHECK)
        }
    }

    private fun offering() = _state.value.let { it is UpdateState.Ready || it is UpdateState.Available || it is UpdateState.RestartToFinish }

    private suspend fun checkLocked() {
        val installed = installedVersion()
        if (running != null && installed != null && installed > running) {
            discardPending()
            _state.value = UpdateState.RestartToFinish(installed)
            return
        }
        if (_state.value is UpdateState.RestartToFinish) return

        val offering = offering()
        if (!offering) _state.value = UpdateState.Checking
        val release = source.latest()
        if (release == null) {
            if (!offering) _state.value = UpdateState.Failed(COULD_NOT_CHECK)
            return
        }
        if (running == null || release.version <= running) {
            if (!offering) _state.value = UpdateState.Idle
            return
        }
        if (type is InstallType.Tarball && !type.writable) {
            _state.value = UpdateState.Available(release.version, release.notesUrl)
            return
        }
        val offered = (_state.value as? UpdateState.Ready)?.version
        if (offered != null && offered >= release.version) return

        discardPending()
        pending = release
        if (autoDownload()) fetch(release) else _state.value = UpdateState.Ready(release.version, null)
    }

    /** Downloads and verifies [release]'s asset; Ready with the file, or Failed (and nothing kept). */
    private suspend fun fetch(release: Release): Path? {
        val name = assetFor(type, release.version)
        val assetUrl = name?.let { taggedLink(release, it) }
        val sumsUrl = taggedLink(release, SUMS)
        val signatureUrl = taggedLink(release, SUMS_SIGNATURE)
        if (name == null || assetUrl == null || sumsUrl == null || signatureUrl == null) {
            discardPending()
            _state.value = UpdateState.Failed("Silage ${release.version} has no download for this copy")
            return null
        }
        _state.value = UpdateState.Downloading(release.version, 0f)
        val part = updatesDir.resolve("$name.part")
        val sumsFile = updatesDir.resolve("$SUMS.part")
        val signatureFile = updatesDir.resolve("$SUMS_SIGNATURE.part")
        var file: Path? = null
        try {
            Files.createDirectories(updatesDir)
            download(sumsUrl, sumsFile) {}
            download(signatureUrl, signatureFile) {}
            download(assetUrl, part) { _state.value = UpdateState.Downloading(release.version, it.coerceIn(0f, 1f)) }
            if (verifier.verify(Files.readAllBytes(sumsFile), Files.readString(signatureFile), name, part)) {
                file = Files.move(part, updatesDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            discardPending()
            _state.value = UpdateState.Failed("Couldn't download Silage ${release.version}")
            return null
        } finally {
            Files.deleteIfExists(sumsFile)
            Files.deleteIfExists(signatureFile)
            Files.deleteIfExists(part)
        }
        if (file == null) {
            discardPending()
            _state.value = UpdateState.Failed("The download of Silage ${release.version} didn't match its signature")
            return null
        }
        _state.value = UpdateState.Ready(release.version, file)
        return file
    }

    /** [release]'s link for [name], only if it is under the release's own tag (never `latest`). */
    private fun taggedLink(release: Release, name: String): String? {
        val url = release.assets[name] ?: return null
        val path = try {
            URI(url).path
        } catch (_: Exception) {
            return null
        }
        return url.takeIf { path.endsWith("/releases/download/${release.tag}/$name") }
    }

    /** Forgets the offered update and deletes everything downloaded for it. */
    private fun discardPending() {
        pending = null
        if (!Files.isDirectory(updatesDir)) return
        Files.list(updatesDir).use { files -> files.forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        const val FIRST_CHECK_DELAY_MS = 20_000L
        private const val SUMS = "SHA256SUMS"
        private const val SUMS_SIGNATURE = "SHA256SUMS.sig"
        private const val COULD_NOT_CHECK = "Couldn't check for updates"
    }
}
