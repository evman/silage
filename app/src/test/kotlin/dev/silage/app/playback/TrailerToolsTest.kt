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
}
