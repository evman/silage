package dev.silage.app.update

import dev.silage.app.BuildInfo
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published release: [assets] maps each file name to its download link under the release's [tag]. */
data class Release(val tag: String, val version: Version, val notesUrl: String, val assets: Map<String, String>)

interface ReleaseSource {
    /** The newest release, or null when it can't be found out (offline, rate-limited, anything). */
    suspend fun latest(): Release?
}

/** The file a [type] install updates from in release [version]; null when it doesn't update itself. */
fun assetFor(type: InstallType, version: Version): String? = when (type) {
    is InstallType.WindowsInstaller -> "Silage-$version-Setup.exe"
    InstallType.Deb -> "silage_${version}_amd64.deb"
    InstallType.Rpm -> "silage-$version-1.x86_64.rpm"
    InstallType.Pacman -> "silage-bin-$version-1-x86_64.pkg.tar.zst"
    is InstallType.Tarball -> "silage-$version-linux-x86_64.tar.gz"
    InstallType.Development -> null
}

/** The HTTP client for update checks and downloads; GitHub's asset links redirect. */
fun updateHttpClient(): HttpClient =
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()

/** The latest release of [repo] from the GitHub API (unauthenticated, so rate-limited). */
class GitHubReleaseSource(
    private val http: HttpClient = updateHttpClient(),
    private val repo: String = "evman/silage",
    private val apiBase: String = "https://api.github.com",
    private val appVersion: String = BuildInfo.VERSION,
) : ReleaseSource {
    @Serializable
    private data class GitHubAsset(val name: String, @SerialName("browser_download_url") val url: String)

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val htmlUrl: String,
        val assets: List<GitHubAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latest(): Release? {
        val request = HttpRequest.newBuilder(URI("$apiBase/repos/$repo/releases/latest"))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Silage/$appVersion")
            .timeout(Duration.ofSeconds(30))
            .build()
        return try {
            val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            if (response.statusCode() != 200) return null
            val release = json.decodeFromString<GitHubRelease>(response.body())
            Release(
                tag = release.tag,
                version = Version.parse(release.tag) ?: return null,
                notesUrl = release.htmlUrl,
                assets = release.assets.associate { it.name to it.url },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Downloads with [http] into a file, telling progress (0 to 1, when the size is known) as it goes;
 * throws when the server doesn't answer 200.
 */
fun httpDownloader(http: HttpClient = updateHttpClient()): suspend (url: String, to: Path, progress: (Float) -> Unit) -> Unit =
    { url, to, progress ->
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI(url))
                .header("User-Agent", "Silage/${BuildInfo.VERSION}")
                .timeout(Duration.ofMinutes(10))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                check(response.statusCode() == 200) { "The download failed (HTTP ${response.statusCode()})" }
                val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
                Files.newOutputStream(to).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
    }
