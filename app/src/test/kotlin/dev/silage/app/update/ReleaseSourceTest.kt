package dev.silage.app.update

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReleaseSourceTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val requests = mutableListOf<Pair<String, Map<String, String?>>>()

    private fun respond(path: String, status: Int, body: String) {
        server.createContext(path) { exchange ->
            synchronized(requests) {
                requests += exchange.requestURI.path to mapOf(
                    "Accept" to exchange.requestHeaders.getFirst("Accept"),
                    "User-Agent" to exchange.requestHeaders.getFirst("User-Agent"),
                )
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun source() = GitHubReleaseSource(HttpClient.newHttpClient(), "evman/silage", apiBase = base, appVersion = "0.5.0")

    @AfterTest fun stop() {
        server.stop(0)
    }

    @Test fun readsTagAndAssets() = runBlocking<Unit> {
        val download = "https://github.com/evman/silage/releases/download/v0.5.1"
        respond(
            "/repos/evman/silage/releases/latest", 200,
            """
            {"tag_name": "v0.5.1", "html_url": "https://github.com/evman/silage/releases/tag/v0.5.1",
             "name": "Silage 0.5.1", "draft": false, "author": {"login": "evman"},
             "assets": [
               {"name": "silage_0.5.1_amd64.deb", "browser_download_url": "$download/silage_0.5.1_amd64.deb", "size": 1},
               {"name": "SHA256SUMS", "browser_download_url": "$download/SHA256SUMS"}
             ]}
            """.trimIndent(),
        )
        server.start()
        assertEquals(
            Release(
                tag = "v0.5.1",
                version = Version(0, 5, 1),
                notesUrl = "https://github.com/evman/silage/releases/tag/v0.5.1",
                assets = mapOf(
                    "silage_0.5.1_amd64.deb" to "$download/silage_0.5.1_amd64.deb",
                    "SHA256SUMS" to "$download/SHA256SUMS",
                ),
            ),
            source().latest(),
        )
        assertEquals(
            listOf("/repos/evman/silage/releases/latest" to mapOf<String, String?>("Accept" to "application/vnd.github+json", "User-Agent" to "Silage/0.5.0")),
            requests,
        )
    }

    @Test fun rateLimitedCheckIsSilent() = runBlocking<Unit> {
        respond("/repos/evman/silage/releases/latest", 403, """{"message": "API rate limit exceeded"}""")
        respond("/repos/other/silage/releases/latest", 429, "")
        server.start()
        assertNull(source().latest())
        assertNull(GitHubReleaseSource(HttpClient.newHttpClient(), "other/silage", apiBase = base, appVersion = "0.5.0").latest())
        // Nothing listening at all.
        server.stop(0)
        assertNull(source().latest())
    }

    @Test fun malformedJsonIsNull() = runBlocking<Unit> {
        respond("/repos/evman/silage/releases/latest", 200, """{"tag_name": "v0.5.1", "assets": [""")
        respond("/repos/a/silage/releases/latest", 200, """{"tag_name": "v0.5.1-rc.1", "html_url": "x", "assets": []}""")
        respond("/repos/b/silage/releases/latest", 200, """{"html_url": "x", "assets": []}""")
        server.start()
        assertNull(source().latest())
        for (repo in listOf("a/silage", "b/silage")) {
            assertNull(GitHubReleaseSource(HttpClient.newHttpClient(), repo, apiBase = base, appVersion = "0.5.0").latest(), repo)
        }
    }

    @Test fun downloadsTheBodyWithProgress() = runBlocking<Unit> {
        val body = "x".repeat(200_000)
        respond("/file", 200, body)
        respond("/missing", 404, "not found")
        server.start()
        val dir = createTempDirectory("silage-download")
        try {
            val progress = mutableListOf<Float>()
            val download = httpDownloader(HttpClient.newHttpClient())
            download("$base/file", dir.resolve("file.part")) { progress += it }
            assertEquals(body, dir.resolve("file.part").readText())
            assertTrue(progress.isNotEmpty() && progress.zipWithNext().all { (a, b) -> a <= b }, progress.toString())
            assertEquals(1f, progress.last())
            assertFailsWith<Exception> { download("$base/missing", dir.resolve("missing.part")) {} }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
