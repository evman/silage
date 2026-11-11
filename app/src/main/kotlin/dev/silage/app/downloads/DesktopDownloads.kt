package dev.silage.app.downloads

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadRequest
import org.siloserver.silo.model.download.DownloadSidecar
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.DownloadStatusEvent
import org.siloserver.silo.model.download.OFFLINE_ARTWORK_POSTER
import org.siloserver.silo.model.download.OfflineSubtitleFile
import org.siloserver.silo.model.download.decodeOfflineManifestArtwork
import org.siloserver.silo.model.download.decodeOfflineManifestTracks
import org.siloserver.silo.model.download.isOfflineArtworkFetchUrl
import org.siloserver.silo.model.download.isOfflineSubtitleFetchUrl
import org.siloserver.silo.model.download.offlineSubtitleExtension
import org.siloserver.silo.model.download.offlineSubtitleFormat
import org.siloserver.silo.model.download.statusEnum
import org.siloserver.silo.model.download.toOfflineTrackInfo
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.apiv2.managedDownloadAuth
import org.siloserver.silo.repository.DownloadsRepository
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** What to download: a title or an episode, and the names to show for it with no server. */
data class DownloadSpec(
    val contentId: String,
    val fileId: Int,
    val title: String,
    val subtitle: String? = null,
    val seriesTitle: String? = null,
    val seriesContentId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val year: Int? = null,
    val durationSeconds: Double? = null,
)

/** One download on this PC, and how far it has got. */
data class LocalDownload(
    val sidecar: DownloadSidecar,
    val directory: Path,
    /** Bytes on disk, updated as they arrive. */
    val bytes: Long = 0,
    /** Why it stopped, until it's tried again. */
    val problem: String? = null,
) {
    val id: String get() = sidecar.record.id
    val status: DownloadStatus get() = sidecar.record.statusEnum()
    val finished: Boolean get() = status == DownloadStatus.Completed && sidecar.localUri != null
    val total: Long get() = sidecar.record.fileSize
}

/** A folder or file name made from a server's id: nothing that can climb out of the downloads folder. */
internal fun safeName(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").ifEmpty { "_" }

/** The extension in a Content-Disposition header's file name, if it looks like one. */
internal fun extensionFrom(contentDisposition: String?): String? =
    contentDisposition?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        ?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }

/** Where a transfer resumes: what's already on disk, or nothing when that's more than the file holds. */
internal fun resumeFrom(onDisk: Long, total: Long): Long = if (total > 0 && onDisk > total) 0 else onDisk

/** One position to send to the server, watched at [at] with no connection. */
internal data class PositionSync(val contentId: String, val seconds: Double, val durationSeconds: Double?, val at: String)

/** "61.5 2026-10-31T12:00:00Z" as written beside the download; null when damaged. */
internal fun unsyncedPosition(text: String): Pair<Double, String>? {
    val parts = text.trim().split(' ')
    val seconds = parts.firstOrNull()?.toDoubleOrNull() ?: return null
    return seconds to (parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null)
}

/** The body of `POST /api/v2/sync/progress`: the server merges on `updated_at`, newest wins. */
internal fun positionSyncBody(items: List<PositionSync>): String = buildJsonObject {
    putJsonArray("items") {
        items.forEach { item ->
            addJsonObject {
                put("media_item_id", item.contentId)
                put("position_ms", (item.seconds * 1000).toLong())
                put("duration_ms", ((item.durationSeconds ?: 0.0) * 1000).toLong())
                put("updated_at", item.at)
            }
        }
    }
}.toString()

/** Which items of a sync the server accepted. */
internal fun syncedIndexes(body: String): Set<Int> = runCatching {
    Json.parseToJsonElement(body).jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { element ->
        val item = element.jsonObject
        item["index"]?.jsonPrimitive?.intOrNull?.takeIf { item["status"]?.jsonPrimitive?.contentOrNull == "success" }
    }.toSet()
}.getOrDefault(emptySet())

/**
 * Downloads on this PC, one at a time, in `<data>/downloads/<server>/<profile>/<id>/`:
 * the file, its subtitles and poster, and a `record.json` with what's needed to list
 * and play it with no server. Survives restarts, resuming where it stopped. The
 * server's registry stays in step through [DownloadsRepository].
 */
class DesktopDownloads(
    private val repository: DownloadsRepository,
    private val client: HttpClient,
    private val authorities: DurableLoginAuthorityProvider,
    private val root: Path,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _rows = MutableStateFlow<List<LocalDownload>>(emptyList())
    val rows: StateFlow<List<LocalDownload>> = _rows

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private var worker: Job? = null
    private var owner: DurableLoginAuthority? = null
    private var active: Pair<String, Job>? = null

    private fun folderOf(authority: DurableLoginAuthority): Path =
        root.resolve(safeName(authority.scope.serverId)).resolve(safeName(authority.scope.profileId.orEmpty()))

    /** Lists this profile's downloads from disk, then starts on any unfinished and catches up with the server. */
    suspend fun start() = lock.withLock {
        val authority = runCatching { authorities.snapshotDurableLoginAuthority() }.getOrNull()
        if (authority?.scope?.serverId != owner?.scope?.serverId || authority?.scope?.profileId != owner?.scope?.profileId) {
            active?.second?.cancelAndJoin()
            active = null
            owner = authority
            _rows.value = authority?.let { readAll(folderOf(it)) }.orEmpty()
            repository.seedFromSidecars(_rows.value.map { it.sidecar.record })
        }
        if (worker == null) worker = scope.launch { run() }
        wake.trySend(Unit)
        Unit
    }

    /** For the profile screen: whether there's something to play when the server can't be reached. */
    suspend fun hasDownloadsOffline(): Boolean {
        start()
        return _rows.value.any { it.finished }
    }

    private fun readAll(folder: Path): List<LocalDownload> {
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { dirs -> dirs.toList() }.mapNotNull { dir ->
            val file = dir.resolve(RecordFile)
            if (!Files.isRegularFile(file)) return@mapNotNull null
            val sidecar = runCatching { json.decodeFromString(DownloadSidecar.serializer(), Files.readString(file)) }.getOrNull() ?: return@mapNotNull null
            val media = sidecar.localUri?.let { dir.resolve(it) } ?: dir.resolve(PartFile)
            LocalDownload(sidecar, dir, runCatching { Files.size(media) }.getOrDefault(0L))
        }.filter { !it.finished || Files.isRegularFile(it.directory.resolve(it.sidecar.localUri!!)) }
            .sortedBy { it.sidecar.record.createdAt }
    }

    private fun save(row: LocalDownload) {
        Files.createDirectories(row.directory)
        val temp = Files.createTempFile(row.directory, "record", ".tmp")
        Files.writeString(temp, json.encodeToString(DownloadSidecar.serializer(), row.sidecar))
        Files.move(temp, row.directory.resolve(RecordFile), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun publish(id: String, change: (LocalDownload) -> LocalDownload) =
        _rows.update { list -> list.map { if (it.id == id) change(it) else it } }

    /** Asks the server for this download and queues it. Returns what went wrong, or null. */
    suspend fun request(spec: DownloadSpec, quality: DownloadQuality): String? {
        start()
        val authority = owner ?: return "Sign in to a profile to download."
        val result = repository.create(
            DownloadRequest(contentId = spec.contentId, fileId = spec.fileId, quality = quality.wire, targetBitrateKbps = quality.targetBitrateKbps),
            authority,
        )
        val record = when (result) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return result.message
            is ApiResult.NetworkError -> return "Can't reach the server. Try again."
        }
        if (_rows.value.any { it.id == record.id }) { wake.trySend(Unit); return null }
        val row = LocalDownload(
            DownloadSidecar(record, spec.title, spec.subtitle, seriesTitle = spec.seriesTitle, seriesContentId = spec.seriesContentId,
                seasonNumber = spec.seasonNumber, episodeNumber = spec.episodeNumber, year = spec.year, durationSeconds = spec.durationSeconds,
                updatedAtMs = System.currentTimeMillis()),
            folderOf(authority).resolve(safeName(record.id)),
        )
        save(row)
        _rows.update { it + row }
        wake.trySend(Unit)
        return null
    }

    /** The row for this title's file, if it's on this PC or on its way. */
    fun forFile(fileId: Int?): LocalDownload? = fileId?.let { id -> _rows.value.firstOrNull { it.sidecar.record.mediaFileId == id } }

    /** Removes it from this PC now; the server hears when it can be reached. */
    suspend fun remove(id: String) {
        val row = _rows.value.firstOrNull { it.id == id } ?: return
        active?.takeIf { it.first == id }?.second?.cancelAndJoin()
        val authority = owner
        if (authority != null) runCatching {
            repository.enqueueDurableDelete(authority.scope.serverId, authority.scope.accountId.orEmpty(), authority.scope.profileId.orEmpty(), id, row.sidecar.record.mediaFileId)
        }
        _rows.update { list -> list.filterNot { it.id == id } }
        withRetries { row.directory.toFile().deleteRecursively() }
        scope.launch { syncWithServer() }
        wake.trySend(Unit)
    }

    /** Tries a stopped download again. */
    fun retry(id: String) {
        publish(id) { it.copy(problem = null) }
        wake.trySend(Unit)
    }

    private inline fun withRetries(action: () -> Boolean) { repeat(5) { if (action()) return; Thread.sleep(200) } }

    private suspend fun syncWithServer() {
        val authority = owner ?: return
        val onDisk = _rows.value.mapTo(HashSet()) { it.id }
        val result = repository.refresh(onDisk, authority.scope.serverId, authority.scope.profileId)
        if (result !is ApiResult.Success) return
        val fresh = repository.records.value.associateBy { it.id }
        _rows.value.forEach { row ->
            val server = fresh[row.id] ?: return@forEach
            val local = row.sidecar.record
            if (row.finished) {
                // A completion that didn't reach the server before: tell it again.
                if (server.statusEnum() != DownloadStatus.Completed) reportCompleted(local.copy(revision = server.revision), authority)
            } else if (server.status != local.status || server.revision != local.revision || server.fileSize != local.fileSize) {
                val next = row.copy(sidecar = row.sidecar.copy(record = server.copy(bytesSent = local.bytesSent)))
                save(next)
                publish(row.id) { next.copy(bytes = it.bytes, problem = it.problem) }
            }
        }
        syncPositions(authority)
    }

    private suspend fun reportCompleted(record: DownloadRecord, authority: DurableLoginAuthority) {
        val revision = record.revision ?: return
        val at = record.statusEventAt ?: java.time.Instant.now().toString()
        repository.reportStatus(record.id, DownloadStatusEvent(DownloadStatus.Completed.wire, at, revision), authority)
    }

    private suspend fun run() {
        while (true) {
            // A server that can't be reached is normal here; the worker carries on.
            try { syncWithServer() } catch (e: CancellationException) { throw e } catch (e: Exception) { System.err.println("Silage: couldn't sync downloads: ${e}") }
            val next = _rows.value.firstOrNull { it.problem == null && !it.finished && it.status.isFetchable() }
            if (next != null) {
                val job = scope.launch { transferOne(next) }
                active = next.id to job
                job.join()
                active = null
                continue
            }
            // Waiting for the server to prepare a converted copy, or for something new.
            withTimeoutOrNull(if (_rows.value.any { it.status == DownloadStatus.Preparing }) PrepareCheckMs else IdleCheckMs) { wake.receive() }
        }
    }

    private fun DownloadStatus.isFetchable() = this == DownloadStatus.Ready || this == DownloadStatus.Queued || this == DownloadStatus.Downloading

    private suspend fun transferOne(row: LocalDownload) {
        val authority = owner ?: return
        try {
            transfer(row, authority)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Silage: download stopped: ${e::class.simpleName}: ${e.message}")
            val message = e.message?.takeIf { e is DownloadProblem } ?: "The connection dropped. Trying again shortly."
            publish(row.id) { it.copy(problem = message) }
            // A dropped connection clears itself; a refusal waits for Try again.
            if (e !is DownloadProblem) scope.launch {
                kotlinx.coroutines.delay(RetryAfterMs)
                retry(row.id)
            }
        }
    }

    private class DownloadProblem(message: String) : Exception(message)

    private suspend fun transfer(row: LocalDownload, authority: DurableLoginAuthority) {
        Files.createDirectories(row.directory)
        val part = row.directory.resolve(PartFile)
        val record = row.sidecar.record
        val owned = authority.scope
        val onDisk = resumeFrom(runCatching { Files.size(part) }.getOrDefault(0L), record.fileSize)
        if (record.fileSize > 0 && row.directory.toFile().usableSpace < record.fileSize - onDisk)
            throw DownloadProblem("Not enough disk space for this download.")
        record.revision?.let { repository.reportStatus(record.id, DownloadStatusEvent(DownloadStatus.Downloading.wire, java.time.Instant.now().toString(), it), authority) }
        var extension: String? = null
        client.prepareGet("/api/v2/downloads/${record.id.encodeURLPathPart()}/file") {
            managedDownloadAuth(owned)
            // Only the connection may stall out: a long film takes as long as it takes.
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = StallMs }
            if (onDisk > 0) {
                header(HttpHeaders.Range, "bytes=$onDisk-")
                row.sidecar.resumeValidator?.let { header(HttpHeaders.IfRange, it) }
            }
        }.execute { response ->
            val resuming = response.status == HttpStatusCode.PartialContent
            if (response.status != HttpStatusCode.OK && !resuming) {
                if (response.status.value >= 500) error("server said ${response.status.value}")
                throw DownloadProblem("The server refused this download (${response.status.value}).")
            }
            extension = extensionFrom(response.headers[HttpHeaders.ContentDisposition])
            val validator = response.headers[HttpHeaders.ETag]?.takeUnless { it.startsWith("W/") } ?: response.headers[HttpHeaders.LastModified]
            if (validator != row.sidecar.resumeValidator) {
                val next = row.copy(sidecar = row.sidecar.copy(resumeValidator = validator))
                save(next)
                publish(row.id) { it.copy(sidecar = next.sidecar) }
            }
            val body = response.bodyAsChannel()
            val options = if (resuming) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            FileChannel.open(part, *options).use { out ->
                var written = if (resuming) onDisk else 0L
                var shownAt = 0L
                val buffer = ByteArray(BufferSize)
                while (true) {
                    val n = body.readAvailable(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    val bytes = ByteBuffer.wrap(buffer, 0, n)
                    while (bytes.hasRemaining()) out.write(bytes)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - shownAt > ProgressEveryMs) { shownAt = now; val w = written; publish(row.id) { it.copy(bytes = w) } }
                }
                if (record.fileSize > 0 && written != record.fileSize) error("stopped at $written of ${record.fileSize} bytes")
            }
        }
        val name = "media.${extension ?: "mkv"}"
        Files.move(part, row.directory.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        val extras = runCatching { saveExtras(record.id, row.directory, owned) }.getOrNull()
        val at = java.time.Instant.now().toString()
        val completed = record.copy(status = DownloadStatus.Completed.wire, completedAt = at, statusEventAt = at, bytesSent = runCatching { Files.size(row.directory.resolve(name)) }.getOrDefault(record.fileSize))
        val latest = _rows.value.firstOrNull { it.id == row.id } ?: row
        val next = latest.copy(
            sidecar = latest.sidecar.copy(record = completed, localUri = name, fileName = name, offlineTracks = extras?.first, offlinePosterPath = extras?.second, updatedAtMs = System.currentTimeMillis()),
            bytes = completed.bytesSent, problem = null,
        )
        save(next)
        publish(row.id) { next }
        repository.upsertLocal(completed)
        runCatching { reportCompleted(completed, authority) }
    }

    /** The manifest's subtitles and poster, so they're here with no server. Missing ones just aren't offered. */
    private suspend fun saveExtras(id: String, directory: Path, owned: org.siloserver.silo.network.AuthScopeSnapshot): Pair<org.siloserver.silo.model.download.OfflineTrackInfo?, String?> {
        val response = client.get("/api/v2/downloads/${id.encodeURLPathPart()}/manifest") { managedDownloadAuth(owned) }
        if (!response.status.isSuccess()) return null to null
        val body = response.bodyAsText()
        val tracks = decodeOfflineManifestTracks(body)
        val subtitles = tracks?.subtitles.orEmpty().mapIndexedNotNull { i, s ->
            val format = offlineSubtitleFormat(s.format) ?: return@mapIndexedNotNull null
            val url = s.fetchUrl.trim().takeIf(::isOfflineSubtitleFetchUrl) ?: return@mapIndexedNotNull null
            runCatching {
                val r = client.get(url) { managedDownloadAuth(owned) }
                if (!r.status.isSuccess()) return@runCatching null
                val file = directory.resolve("subtitle-$i.${offlineSubtitleExtension(format)}")
                Files.write(file, r.bodyAsBytes())
                OfflineSubtitleFile(file.toString(), format, s.language, s.title, s.forced, s.hearingImpaired, url, s.revision)
            }.getOrNull()
        }
        val poster = decodeOfflineManifestArtwork(body)?.artworkUrls?.poster?.trim()?.takeIf { isOfflineArtworkFetchUrl(it, OFFLINE_ARTWORK_POSTER) }?.let { url ->
            runCatching {
                val r = client.get(url) { managedDownloadAuth(owned) }
                if (!r.status.isSuccess()) return@runCatching null
                directory.resolve("poster.img").also { Files.write(it, r.bodyAsBytes()) }.toString()
            }.getOrNull()
        }
        return tracks?.toOfflineTrackInfo(subtitles) to poster
    }

    /** Where this video was left, read back when it's played again. */
    fun position(id: String): Double? = _rows.value.firstOrNull { it.id == id }?.let { row ->
        runCatching { Files.readString(row.directory.resolve(PositionFile)).trim().toDouble() }.getOrNull()
    }

    fun savePosition(id: String, seconds: Double?) {
        val row = _rows.value.firstOrNull { it.id == id } ?: return
        runCatching {
            val file = row.directory.resolve(PositionFile)
            if (seconds == null) Files.deleteIfExists(file) else {
                Files.writeString(file, seconds.toString())
                noteUnsynced(row, seconds)
            }
        }
    }

    /** It played to the end: nothing to resume here, and the server hears it was seen to [lastPosition]. */
    fun finishedAt(id: String, lastPosition: Double?) {
        savePosition(id, null)
        val row = _rows.value.firstOrNull { it.id == id } ?: return
        lastPosition?.let { position -> runCatching { noteUnsynced(row, maxOf(position, row.sidecar.durationSeconds ?: 0.0)) } }
    }

    /** Asks the worker to tell the server now, if it can be reached (after a video closes). */
    fun syncPositionsSoon() { wake.trySend(Unit) }

    private fun noteUnsynced(row: LocalDownload, seconds: Double) {
        Files.writeString(row.directory.resolve(UnsyncedFile), "$seconds ${java.time.Instant.now()}")
    }

    /** Sends what was watched with no connection; each stays on disk until the server has it. */
    private suspend fun syncPositions(authority: DurableLoginAuthority) {
        val pending = _rows.value.mapNotNull { row ->
            val file = row.directory.resolve(UnsyncedFile)
            val text = runCatching { Files.readString(file) }.getOrNull() ?: return@mapNotNull null
            unsyncedPosition(text)?.let { Triple(file, text, row to it) }
        }.take(SyncBatch)
        if (pending.isEmpty()) return
        val response = client.post("/api/v2/sync/progress") {
            managedDownloadAuth(authority.scope)
            contentType(ContentType.Application.Json)
            setBody(positionSyncBody(pending.map { (_, _, entry) -> PositionSync(entry.first.sidecar.record.contentId, entry.second.first, entry.first.sidecar.durationSeconds, entry.second.second) }))
        }
        if (!response.status.isSuccess()) return
        val accepted = syncedIndexes(response.bodyAsText())
        pending.forEachIndexed { i, (file, text, _) ->
            // Unless it was watched further while this was on its way.
            if (i in accepted && runCatching { Files.readString(file) }.getOrNull() == text) runCatching { Files.deleteIfExists(file) }
        }
    }

    private companion object {
        const val UnsyncedFile = "position-unsynced.txt"
        const val SyncBatch = 100
        const val RecordFile = "record.json"
        const val PartFile = "media.part"
        const val PositionFile = "position.txt"
        const val BufferSize = 1 shl 20
        const val ProgressEveryMs = 500L
        const val StallMs = 120_000L
        const val RetryAfterMs = 30_000L
        const val PrepareCheckMs = 15_000L
        const val IdleCheckMs = 300_000L
    }
}
