package dev.silage.player

import java.nio.file.Files
import java.nio.file.Path

/** Generates small media files with ffmpeg once per JVM. */
object TestClips {
    /** Whether ffmpeg is on PATH; the tests that need a clip are skipped without it. */
    val available: Boolean by lazy {
        runCatching {
            val p = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor() == 0
        }.getOrDefault(false)
    }

    private val dir: Path by lazy { Files.createTempDirectory("silage-clips") }

    /** 5 s, 320x240, 24 fps H.264 + AAC sine, one SRT subtitle track. */
    val fiveSeconds: Path by lazy {
        val srt = dir.resolve("subs.srt").also {
            Files.writeString(it, "1\n00:00:00,500 --> 00:00:04,000\nHello\n")
        }
        val out = dir.resolve("five.mkv")
        val p = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440",
            "-i", srt.toString(),
            "-t", "5", "-map", "0:v", "-map", "1:a", "-map", "2:s",
            "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-c:s", "srt",
            out.toString(),
        ).inheritIO().start()
        check(p.waitFor() == 0) { "ffmpeg failed to create test clip" }
        out
    }
}
