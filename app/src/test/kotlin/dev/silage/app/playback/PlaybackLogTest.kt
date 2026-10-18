package dev.silage.app.playback

import dev.silage.app.XdgPaths
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLogTest {
    @Test fun productionPlayerOptionsDoNotPersistUnredactableNativeUrls() {
        val options = playbackMpvOptions(VideoOutput.Embedded(123), paused = true, muted = true)
        assertFalse(options.containsKey("log-file"))
        kotlin.test.assertEquals("yes", options["pause"])
        kotlin.test.assertEquals("0", options["volume"])
    }

    @Test fun failureLogDropsSignedQueries() {
        val lines = PlaybackLog.redactLog(listOf(
            "[ffmpeg] error: https://nas:8096/api/v2/playback/transcode/abc/master.m3u8?st=SECRET789&e=1: Server returned 404 Not Found",
            "[cplayer] warn: plain line",
        ))
        assertFalse(lines.any { "SECRET789" in it }, lines.toString())
        assertContains(lines[0], "https://nas:8096/api/v2/playback/transcode/abc/master.m3u8?…")
        assertContains(lines[0], "404 Not Found")
        kotlin.test.assertEquals("[cplayer] warn: plain line", lines[1])
    }

    private val decision = SiloJson.decodeFromString(
        PlaybackDecisionResponseV3.serializer(),
        """{"protocol_version":3,"session_id":"s1","playback_plan":{"plan_id":"p1","delivery":"original_http",
            "stream":{"url":"/api/v2/stream/77?token=SECRET123&x=1","protocol":"http_progressive","headers":{"Authorization":"Bearer SECRET456"}},
            "decision_reason":"direct",
            "subtitle":{"mode":"render","inventory":[{"track_id":"t1","combined_index":0,"source":"external","language":"eng","delivery":"sidecar","url":"/api/v2/subs/1.srt"}]}}}""",
    )

    @Test fun signedStreamQueryIsRedacted() {
        val signed = SiloJson.decodeFromString(PlaybackDecisionResponseV3.serializer(),
            SiloJson.encodeToString(PlaybackDecisionResponseV3.serializer(), decision)
                .replace("token=SECRET123", "st=DUMMY_SIGNED&sig=DUMMY_SIGNATURE"))
        val text = PlaybackLog.describe(signed)
        assertFalse(text.contains("DUMMY_SIGNED"), "signed stream credential leaked")
        assertFalse(text.contains("DUMMY_SIGNATURE"), "stream signature leaked")
    }

    @Test fun describesThePlanWithoutCredentials() {
        val text = PlaybackLog.describe(decision)
        assertContains(text, "original_http")
        assertContains(text, "/api/v2/subs/1.srt")
        assertFalse("SECRET123" in text, text)
        assertFalse("SECRET456" in text, text)
    }

    @Test fun kdeDisplayConfigIsReadFromTheUserConfigDirNotSilages() {
        val p = XdgPaths.kwinOutputConfig.toString().replace('\\', '/')
        assertTrue(p.endsWith("/kwinoutputconfig.json"), p)
        assertFalse("/silage/" in p, p)
    }
}
