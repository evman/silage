package dev.silage.app.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadRequest
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.DownloadStatusEvent
import org.siloserver.silo.model.download.DownloadsListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.SiloDeviceMetadata
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.DownloadsApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.DownloadCreationV2Api
import org.siloserver.silo.network.apiv2.DownloadRegistryV2Api
import org.siloserver.silo.repository.DownloadsRepository
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object NoDevices : DeviceMetadataProvider {
    override suspend fun current(): SiloDeviceMetadata? = null
}

private fun registry() = DownloadRegistryV2Api(HttpClient(), TokenManagerImpl(), NoDevices, ApiV2Gate.Unrestricted)

/** The registry as a list in memory. */
private class FakeRegistry(val record: DownloadRecord) : DownloadsApi(registry(), TokenManagerImpl(), DownloadCreationV2Api(HttpClient(), TokenManagerImpl(), NoDevices, registry(), ApiV2Gate.Unrestricted)) {
    val reports = CopyOnWriteArrayList<String>()
    override suspend fun list(scope: AuthScopeSnapshot?) = ApiResult.Success(DownloadsListResponse(listOf(record)))
    override suspend fun create(request: DownloadRequest, scope: AuthScopeSnapshot?) = ApiResult.Success(record)
    override suspend fun reportStatus(id: String, event: DownloadStatusEvent, scope: AuthScopeSnapshot?): ApiResult<DownloadRecord> {
        reports += event.status
        return ApiResult.Success(record)
    }
    override suspend fun delete(id: String, scope: AuthScopeSnapshot?) = ApiResult.Success(Unit)
}

class DesktopDownloadsTest {
    private val root = Files.createTempDirectory("downloads")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val content = ByteArray(5_000) { (it % 251).toByte() }
    private val record = DownloadRecord("dl/1", "movie", mediaFileId = 42, fileSize = content.size.toLong(), kind = "queued", status = "ready", createdAt = "2026-01-01T00:00:00Z", revision = 3)
    private val owner = DurableLoginAuthority("login", AuthScopeSnapshot("server", "profile", "https://silo.example", null))
    private val authorities = object : DurableLoginAuthorityProvider { override suspend fun snapshotDurableLoginAuthority() = owner }
    private val requests = CopyOnWriteArrayList<String>()
    private val ranges = CopyOnWriteArrayList<String>()
    @Volatile private var cutShort = false
    @Volatile private var serverDown = false
    private val syncBodies = CopyOnWriteArrayList<String>()

    private val manifest = """{"delivery_format":"original","subtitles":[
        {"language":"en","format":"srt","fetch_url":"/api/v2/downloads/dl%2F1/subtitles/external:0"},
        {"language":"de","format":"srt","fetch_url":"https://evil.example/x"}],
        "artwork_urls":{"poster":"/api/v2/downloads/dl%2F1/artwork/poster"}}"""

    private val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        requests += path
        when {
            path.endsWith("/file") -> {
                val range = request.headers[HttpHeaders.Range]
                if (range != null) ranges += range
                if (range != null) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    respond(content.copyOfRange(from, content.size), HttpStatusCode.PartialContent)
                } else {
                    val body = if (cutShort) content.copyOf(1_000) else content
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentDisposition, "attachment; filename=\"Film.mp4\""))
                }
            }
            path == "/api/v2/sync/progress" -> {
                syncBodies += (request.body as io.ktor.http.content.TextContent).text
                if (serverDown) respond("", HttpStatusCode.ServiceUnavailable)
                else respond("""{"items":[{"status":"success","index":0,"media_item_id":"movie"}],"summary":{}}""", HttpStatusCode.OK)
            }
            path.endsWith("/manifest") -> respond(manifest, HttpStatusCode.OK)
            path.contains("/subtitles/") -> respond("1\n00:00:01,000 --> 00:00:02,000\nHi\n", HttpStatusCode.OK)
            path.endsWith("/artwork/poster") -> respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }) { install(HttpTimeout) }

    private val api = FakeRegistry(record)
    private fun downloads(repository: DownloadsRepository = DownloadsRepository(api, authorities = authorities, identityTransitions = org.siloserver.silo.network.DefaultIdentityTransitionBarrier())) = DesktopDownloads(repository, client, authorities, root, scope)
    private val spec = DownloadSpec("movie", 42, "Film", "2020")

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!check()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @AfterTest fun tearDown() { scope.cancel(); root.toFile().deleteRecursively() }

    @Test fun downloadsTheFileItsSubtitlesAndPoster() = runBlocking {
        val d = downloads()
        assertEquals(null, d.request(spec, DownloadQuality.Original))
        waitFor("the download") { d.rows.value.singleOrNull()?.finished == true }
        val row = d.rows.value.single()
        assertEquals("media.mp4", row.sidecar.localUri)
        assertTrue(content.contentEquals(Files.readAllBytes(row.directory.resolve("media.mp4"))))
        assertEquals(root.resolve("server").resolve("profile").resolve("dl_1"), row.directory)
        // The manifest's link to another site is never followed, with this viewer's sign-in on it.
        assertEquals(listOf("en"), row.sidecar.offlineTracks?.subtitles?.map { it.language })
        assertFalse(requests.any { "evil" in it })
        assertTrue(Files.exists(java.nio.file.Path.of(row.sidecar.offlinePosterPath!!)))
        waitFor("the completion report") { DownloadStatus.Completed.wire in api.reports }
        assertTrue(api.reports.first() == DownloadStatus.Downloading.wire)
    }

    @Test fun aCutOffTransferResumesWhereItStopped() = runBlocking {
        cutShort = true
        val d = downloads()
        d.request(spec, DownloadQuality.Original)
        waitFor("the cut") { d.rows.value.singleOrNull()?.problem != null }
        cutShort = false
        assertFalse(d.rows.value.single().finished)
        d.retry("dl/1")
        waitFor("the rest") { d.rows.value.single().finished }
        assertEquals(listOf("bytes=1000-"), ranges.toList())
        val row = d.rows.value.single()
        assertTrue(content.contentEquals(Files.readAllBytes(row.directory.resolve(row.sidecar.localUri!!))))
    }

    @Test fun finishedDownloadsComeBackAfterARestartAndRemember() = runBlocking {
        val first = downloads()
        first.request(spec, DownloadQuality.Original)
        waitFor("the download") { first.rows.value.singleOrNull()?.finished == true }
        first.savePosition("dl/1", 61.5)
        val again = DesktopDownloads(DownloadsRepository(api, authorities = authorities, identityTransitions = org.siloserver.silo.network.DefaultIdentityTransitionBarrier()), client, authorities, root, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertTrue(again.hasDownloadsOffline())
        assertEquals("Film", again.rows.value.single().sidecar.title)
        assertEquals(61.5, again.position("dl/1"))
    }

    @Test fun offlineProgressReachesTheServerOnceItCanBeReached() = runBlocking {
        val d = downloads()
        d.request(spec, DownloadQuality.Original)
        waitFor("the download") { d.rows.value.singleOrNull()?.finished == true }
        serverDown = true
        d.savePosition("dl/1", 61.5)
        val unsynced = d.rows.value.single().directory.resolve("position-unsynced.txt")
        d.syncPositionsSoon()
        waitFor("the failed try") { syncBodies.isNotEmpty() }
        assertTrue(Files.exists(unsynced))
        val sent = syncBodies.first()
        assertTrue("\"media_item_id\":\"movie\"" in sent && "\"position_ms\":61500" in sent && "\"updated_at\"" in sent, sent)
        serverDown = false
        d.syncPositionsSoon()
        waitFor("the sent position to clear") { !Files.exists(unsynced) }
        assertEquals(61.5, d.position("dl/1"))
    }

    @Test fun onlyAcceptedPositionsCountAsSynced() {
        assertEquals(setOf(1), syncedIndexes("""{"items":[{"status":"failure","index":0},{"status":"success","index":1}]}"""))
        assertEquals(emptySet(), syncedIndexes("not json"))
        assertEquals(null, unsyncedPosition("garbage"))
        assertEquals(1.5 to "2026-10-31T00:00:00Z", unsyncedPosition("1.5 2026-10-31T00:00:00Z"))
    }

    @Test fun removingDeletesTheFilesAndRemembersToTellTheServer() = runBlocking {
        val deletions = FileDownloadDeletions(root.resolve("deletions.json"), authorities, NoDevices)
        val d = downloads(DownloadsRepository(api, deletions, authorities, null, org.siloserver.silo.network.DefaultIdentityTransitionBarrier()))
        d.request(spec, DownloadQuality.Original)
        waitFor("the download") { d.rows.value.singleOrNull()?.finished == true }
        val dir = d.rows.value.single().directory
        d.remove("dl/1")
        assertTrue(d.rows.value.isEmpty())
        waitFor("the files going") { !Files.exists(dir) }
        assertEquals(setOf("dl/1"), deletions.allPendingRecordIds())
    }

    @Test fun aRefusalWaitsForTryAgain() = runBlocking {
        val refusing = HttpClient(MockEngine { respond("", HttpStatusCode.Forbidden) }) { install(HttpTimeout) }
        val d = DesktopDownloads(DownloadsRepository(api, authorities = authorities, identityTransitions = org.siloserver.silo.network.DefaultIdentityTransitionBarrier()), refusing, authorities, root, scope)
        d.request(spec, DownloadQuality.Original)
        waitFor("the refusal") { d.rows.value.singleOrNull()?.problem != null }
        assertEquals("The server refused this download (403).", d.rows.value.single().problem)
        assertEquals("The server refused this download (403).", statusLine(d.rows.value.single()))
    }
}
