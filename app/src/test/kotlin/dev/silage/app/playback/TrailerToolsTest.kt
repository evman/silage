package dev.silage.app.playback

import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailerToolsTest {
    private val hash = "a".repeat(63) + "B"

    @Test fun checksumsAreFoundByFileNameOrAsTheLoneHash() {
        val sums = "${"1".repeat(64)}  yt-dlp\n$hash *yt-dlp.exe\n${"2".repeat(64)}  yt-dlp_x86.exe\n"
        assertEquals(hash.lowercase(), TrailerTools.parseChecksum(sums, "yt-dlp.exe"))
        assertNull(TrailerTools.parseChecksum(sums, "missing.exe"))
        assertEquals(hash.lowercase(), TrailerTools.parseChecksum("$hash  deno-x86_64-pc-windows-msvc.zip\n", null))
        assertEquals(hash.lowercase(), TrailerTools.parseChecksum("$hash\n", null))
        assertNull(TrailerTools.parseChecksum("not a checksum", null))
    }

    @Test fun windowsOptionsPointMpvAtTheDownloadedPrograms() {
        val o = TrailerTools.mpvOptions(Paths.get("C:\\Users\\A B\\tools\\yt-dlp.exe"), Paths.get("C:\\Users\\A B\\tools\\deno.exe"), windows = true)
        assertEquals("yes", o["ytdl"])
        val ytdl = "C:/Users/A B/tools/yt-dlp.exe"
        assertEquals("ytdl_hook-ytdl_path=%${ytdl.length}%$ytdl", o["script-opts"])
        val deno = "deno:C:/Users/A B/tools/deno.exe"
        assertEquals("js-runtimes=%${deno.length}%$deno", o["ytdl-raw-options"])
    }

    @Test fun otherSystemsUseTheYtDlpOnThePath() {
        val o = TrailerTools.mpvOptions(windows = false)
        assertEquals(setOf("ytdl", "ytdl-format"), o.keys)
    }

    @Test fun downloadsAreCheckedAndUnpacked() {
        val dir = Files.createTempDirectory("trailer-tools")
        try {
            val zip = dir.resolve("deno.zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { z ->
                z.putNextEntry(ZipEntry("bin/deno.exe")); z.write("exe".toByteArray()); z.closeEntry()
            }
            val out = dir.resolve("deno.exe")
            TrailerTools.extract(zip, "deno.exe", out)
            assertEquals("exe", Files.readString(out))
            assertEquals("9095bdb859308b62acf04036ffd4adfe366d7f737d276eb6c46ae434f3816c9b", TrailerTools.sha256(out))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun bundledToolsAreFoundBesideTheLauncherAndDownloadedOnesWin() {
        val root = java.nio.file.Files.createTempDirectory("tt")
        try {
            val app = root.resolve("Silage"); val data = root.resolve("data")
            java.nio.file.Files.createDirectories(app.resolve("tools")); java.nio.file.Files.createDirectories(data)
            val launcher = app.resolve("silage.exe").toString()
            val bundled = TrailerTools.bundledDir(launcher)
            assertEquals(app.resolve("tools"), bundled)
            assertNull(TrailerTools.locate("yt-dlp.exe", data, bundled))
            java.nio.file.Files.writeString(app.resolve("tools/yt-dlp.exe"), "b")
            assertEquals(app.resolve("tools/yt-dlp.exe"), TrailerTools.locate("yt-dlp.exe", data, bundled))
            java.nio.file.Files.writeString(data.resolve("yt-dlp.exe"), "d")
            assertEquals(data.resolve("yt-dlp.exe"), TrailerTools.locate("yt-dlp.exe", data, bundled))
            assertNull(TrailerTools.bundledDir(null))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun theFolderBuildOfYtdlpIsPreferredOverTheSingleFile() {
        val root = java.nio.file.Files.createTempDirectory("tt")
        try {
            val data = root.resolve("data"); val bundled = root.resolve("tools")
            java.nio.file.Files.createDirectories(data); java.nio.file.Files.createDirectories(bundled.resolve("yt-dlp"))
            assertNull(TrailerTools.locateYtdlp(data, bundled))
            java.nio.file.Files.writeString(data.resolve("yt-dlp.exe"), "old")
            assertEquals(data.resolve("yt-dlp.exe"), TrailerTools.locateYtdlp(data, bundled))
            java.nio.file.Files.writeString(bundled.resolve("yt-dlp/yt-dlp.exe"), "folder")
            assertEquals(bundled.resolve("yt-dlp/yt-dlp.exe"), TrailerTools.locateYtdlp(data, bundled))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun aWholeZipIsUnpackedButNothingOutsideItsFolder() {
        val dir = Files.createTempDirectory("tt")
        try {
            val zip = dir.resolve("y.zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { z ->
                z.putNextEntry(ZipEntry("_internal/lib.pyd")); z.write("lib".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("yt-dlp.exe")); z.write("exe".toByteArray()); z.closeEntry()
            }
            TrailerTools.extractAll(zip, dir.resolve("out"))
            assertEquals("exe", Files.readString(dir.resolve("out/yt-dlp.exe")))
            assertEquals("lib", Files.readString(dir.resolve("out/_internal/lib.pyd")))
            val bad = dir.resolve("bad.zip")
            ZipOutputStream(Files.newOutputStream(bad)).use { z -> z.putNextEntry(ZipEntry("../escape.txt")); z.write("x".toByteArray()); z.closeEntry() }
            kotlin.test.assertFails { TrailerTools.extractAll(bad, dir.resolve("out2")) }
            kotlin.test.assertFalse(Files.exists(dir.resolve("escape.txt")))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun trailersAskForTheBestQualityWithoutACap() {
        assertEquals("bestvideo*+bestaudio/best", TrailerTools.mpvOptions(windows = false)["ytdl-format"])
        val cmd = TrailerTools.ytdlpCommand(Paths.get("C:\\t\\yt-dlp\\yt-dlp.exe"), Paths.get("C:\\t\\deno.exe"), windows = true)
        assertEquals(listOf("--js-runtimes", "deno:C:\\t\\deno.exe"), cmd.drop(1))
        val direct = TrailerTools.directOptions(TrailerPrefetch.Streams("https://v", "https://a", "UA/1"))
        assertEquals(mapOf("ytdl" to "no", "user-agent" to "UA/1"), direct)
    }

    @Test fun ytdlpsAnswerGivesThePictureAndSoundStreams() {
        val json = """{"id":"x","http_headers":{"User-Agent":"Root/1"},"requested_formats":[
            {"format_id":"401","vcodec":"av01.0.12M.08","acodec":"none","url":"https://g/v?a=1&b=2","http_headers":{"User-Agent":"Fmt/2"}},
            {"format_id":"251","vcodec":"none","acodec":"opus","url":"https://g/a"}]}"""
        val s = TrailerPrefetch.parse(json)!!
        assertEquals(TrailerPrefetch.Streams("https://g/v?a=1&b=2", "https://g/a", "Fmt/2"), s)
        assertEquals("edl://!no_clip;!no_chapters;%19%https://g/v?a=1&b=2;!new_stream;!no_clip;!no_chapters;%11%https://g/a", s.playUrl)
        val single = TrailerPrefetch.parse("""{"url":"https://g/both","http_headers":{"User-Agent":"Root/1"}}""")!!
        assertEquals("https://g/both", single.playUrl)
        assertEquals("Root/1", single.userAgent)
        assertNull(TrailerPrefetch.parse("not json"))
        assertNull(TrailerPrefetch.parse("{}"))
    }
}
