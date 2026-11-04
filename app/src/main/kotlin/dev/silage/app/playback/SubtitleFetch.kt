package dev.silage.app.playback

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Downloads a subtitle stored on the Silo server with the sign-in, for mpv to
 * load as a local file. Needed when the video comes from the server's proxy:
 * mpv sends the sign-in only to the server it streams from (see SubtitleLoad).
 */
object SubtitleFetch {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    /** The saved file (named with the link's extension, which mpv uses), or null if the server refused. */
    fun download(url: String, headers: Map<String, String>, dir: Path): Path? = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET()
        headers.forEach { (k, v) -> request.header(k, v) }
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200 || response.body().isEmpty()) return@runCatching null
        val extension = URI(url).path.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 } ?: "srt"
        Files.createDirectories(dir)
        clearOld(dir)
        Files.createTempFile(dir, "subtitle-", ".$extension").also { Files.write(it, response.body()) }
    }.getOrNull()

    /** Earlier downloads are only needed during their playback: drop ones over a day old. */
    private fun clearOld(dir: Path) = runCatching {
        val cutoff = java.time.Instant.now().minus(Duration.ofDays(1))
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().startsWith("subtitle-") && Files.getLastModifiedTime(it).toInstant() < cutoff }
                .forEach { runCatching { Files.delete(it) } }
        }
    }
}
