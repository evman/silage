package dev.silage.app.playback

import dev.silage.app.XdgPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile

/**
 * What playing a YouTube trailer needs. mpv opens the page through yt-dlp, which in turn needs a
 * JavaScript runtime (Deno) to read YouTube's pages. On Windows Silage downloads both, once, from
 * their official GitHub releases into its own data folder, checking each against the checksum the
 * release publishes. The packaged app already carries both in its `tools` folder, so this is only a fallback. Elsewhere mpv uses whatever yt-dlp the system has.
 */
object TrailerTools {
    private val windows get() = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Where tools downloaded by Silage itself go (and win over the bundled ones, so an update can replace them). */
    val dir: Path get() = XdgPaths.data.resolve("tools")

    /** The `tools` folder shipped beside silage.exe: yt-dlp and Deno come with the app. */
    internal fun bundledDir(launcher: String? = System.getProperty("jpackage.app-path")): Path? =
        launcher?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().parent?.resolve("tools") }

    /** [name] from the downloaded copy if there is one, else the bundled one; null when neither exists. */
    internal fun locate(name: String, downloaded: Path = dir, bundled: Path? = bundledDir()): Path? =
        listOfNotNull(downloaded, bundled).map { it.resolve(name) }.firstOrNull { Files.isRegularFile(it) }

    /**
     * yt-dlp's folder build (`yt-dlp\yt-dlp.exe` beside its `_internal` folder). It starts in a
     * fraction of the time of the single-file `yt-dlp.exe`, which unpacks itself to a temporary
     * folder (and gets scanned there) every time it runs. An older single-file copy still works.
     */
    internal const val YtdlpFolder = "yt-dlp"
    internal fun locateYtdlp(downloaded: Path = dir, bundled: Path? = bundledDir()): Path? =
        locate("$YtdlpFolder/yt-dlp.exe", downloaded, bundled) ?: locate("yt-dlp.exe", downloaded, bundled)

    val ytdlp: Path get() = locateYtdlp() ?: dir.resolve(YtdlpFolder).resolve("yt-dlp.exe")
    val deno: Path get() = locate("deno.exe") ?: dir.resolve("deno.exe")

    internal const val YtdlpZip = "yt-dlp_win.zip"
    internal const val YtdlpZipUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$YtdlpZip"
    internal const val YtdlpSumsUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS"
    internal const val DenoZipUrl = "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip"
    internal const val DenoSumUrl = "$DenoZipUrl.sha256sum"

    /** True when a trailer can play now. */
    fun ready(): Boolean = !windows || (locateYtdlp() != null && locate("deno.exe") != null)

    /**
     * The best picture and sound YouTube has for the video, at any resolution, joined together;
     * else the best single stream. yt-dlp ranks by resolution, then frame rate, HDR and codec.
     */
    internal const val Format = "bestvideo*+bestaudio/best"

    /** yt-dlp as a command, with Deno for YouTube's pages on Windows. */
    internal fun ytdlpCommand(ytdlp: Path = this.ytdlp, deno: Path = this.deno, windows: Boolean = this.windows): List<String> =
        if (windows) listOf(ytdlp.toString(), "--js-runtimes", "deno:" + deno) else listOf("yt-dlp")

    /** mpv options for streams yt-dlp already found (see [TrailerPrefetch]): mpv needn't look them up again. */
    fun directOptions(streams: TrailerPrefetch.Streams): Map<String, String> = buildMap {
        put("ytdl", "no")
        streams.userAgent?.let { put("user-agent", it) }
    }

    /** mpv options that turn on its YouTube support, pointed at the downloaded programs on Windows. */
    fun mpvOptions(ytdlp: Path = this.ytdlp, deno: Path = this.deno, windows: Boolean = this.windows): Map<String, String> = buildMap {
        put("ytdl", "yes")
        put("ytdl-format", Format)
        if (windows) {
            put("script-opts", "ytdl_hook-ytdl_path=" + literal(slashes(ytdlp)))
            put("ytdl-raw-options", "js-runtimes=" + literal("deno:" + slashes(deno)))
        }
    }

    /** mpv's length-prefixed form, so a path with commas or equals signs stays one value. */
    internal fun literal(value: String) = "%${value.length}%$value"

    private fun slashes(path: Path) = path.toString().replace('\\', '/')

    /** The checksum for [name] in a `SHA2-256SUMS` file ("hash *name"), or the lone hash of a `.sha256sum` file when [name] is null. */
    internal fun parseChecksum(text: String, name: String?): String? {
        // Any line with a SHA-256 (64 hex digits) and, when a name is given, that file's name:
        // releases word these files slightly differently ("hash *name", "hash  name", a bare hash).
        val hash = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{64})(?![0-9a-fA-F])")
        return text.lineSequence()
            .filter { name == null || it.contains(name) }
            .firstNotNullOfOrNull { hash.find(it)?.groupValues?.get(1) }
            ?.lowercase()
    }

    internal fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies the zip's entry [entry] (found by file name, wherever it sits) to [target]. */
    internal fun extract(zip: Path, entry: String, target: Path) {
        ZipFile(zip.toFile()).use { z ->
            val e = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == entry }
                ?: error("$entry is not in the download")
            z.getInputStream(e).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    /** Unpacks the whole of [zip] into [target], which must not exist yet. */
    internal fun extractAll(zip: Path, target: Path) {
        val root = target.toAbsolutePath().normalize()
        ZipFile(zip.toFile()).use { z ->
            for (e in z.entries()) {
                val out = root.resolve(e.name).normalize()
                check(out.startsWith(root)) { "The download contains an unsafe path." }
                if (e.isDirectory) { Files.createDirectories(out); continue }
                Files.createDirectories(out.parent)
                z.getInputStream(e).use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** Downloads what is missing; returns null when done, or what went wrong. [status] is told each step. */
    suspend fun install(status: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        runCatching {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build()
            fun get(url: String, into: Path? = null): String? {
                val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).build()
                val response = if (into == null) client.send(request, HttpResponse.BodyHandlers.ofString())
                else client.send(request, HttpResponse.BodyHandlers.ofFile(into))
                check(response.statusCode() == 200) { "The download failed (HTTP ${response.statusCode()})." }
                return if (into == null) response.body() as String else null
            }
            Files.createDirectories(dir)
            if (locateYtdlp() == null) {
                status("Downloading yt-dlp…")
                val want = parseChecksum(get(YtdlpSumsUrl)!!, YtdlpZip) ?: error("yt-dlp's checksum could not be found.")
                val zip = dir.resolve("$YtdlpZip.part")
                val unpacked = dir.resolve("$YtdlpFolder.part")
                get(YtdlpZipUrl, zip)
                try {
                    check(sha256(zip) == want) { "The yt-dlp download did not match its checksum, so it was not used." }
                    deleteTree(unpacked)
                    extractAll(zip, unpacked)
                    check(Files.isRegularFile(unpacked.resolve("yt-dlp.exe"))) { "yt-dlp.exe is not in the download" }
                    deleteTree(dir.resolve(YtdlpFolder))
                    Files.move(unpacked, dir.resolve(YtdlpFolder))
                } finally { Files.deleteIfExists(zip); deleteTree(unpacked) }
            }
            if (locate("deno.exe") == null) {
                status("Downloading Deno…")
                val want = parseChecksum(get(DenoSumUrl)!!, null) ?: error("Deno's checksum could not be found.")
                val zip = dir.resolve("deno.zip.part")
                get(DenoZipUrl, zip)
                try {
                    check(sha256(zip) == want) { "The Deno download did not match its checksum, so it was not used." }
                    extract(zip, "deno.exe", dir.resolve("deno.exe.part"))
                    Files.move(dir.resolve("deno.exe.part"), dir.resolve("deno.exe"), StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(zip) }
            }
        }.fold({ null }, { it.message ?: "Something went wrong while downloading." })
    }
}
