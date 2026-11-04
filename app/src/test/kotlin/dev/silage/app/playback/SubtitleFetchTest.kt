package dev.silage.app.playback

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A server-stored subtitle beside a proxied video: Silage downloads it with the sign-in for mpv. */
class SubtitleFetchTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v2/stream/s/subtitles/4.srt") { ex ->
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer TOKEN"
            val body = if (ok) "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray() else ByteArray(0)
            ex.sendResponseHeaders(if (ok) 200 else 401, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
            ex.close()
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/api/v2/stream/s/subtitles/4.srt?file_id=9"
    private val dir = Files.createTempDirectory("subs")

    @AfterTest fun stop() = server.stop(0)

    @Test fun downloadsWithTheSignInKeepingTheExtension() {
        val file = assertNotNull(SubtitleFetch.download(url, mapOf("Authorization" to "Bearer TOKEN"), dir))
        assertTrue(file.fileName.toString().endsWith(".srt"), "mpv recognises the format by its extension")
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHello\n", file.readText())
    }

    @Test fun earlierDownloadsAreClearedAfterADay() {
        val old = Files.createTempFile(dir, "subtitle-", ".srt")
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2))))
        val recent = Files.createTempFile(dir, "subtitle-", ".vtt")
        assertNotNull(SubtitleFetch.download(url, mapOf("Authorization" to "Bearer TOKEN"), dir))
        assertTrue(!Files.exists(old) && Files.exists(recent))
    }

    @Test fun aRefusedDownloadGivesNothing() {
        assertNull(SubtitleFetch.download(url, emptyMap(), dir))
    }
}
