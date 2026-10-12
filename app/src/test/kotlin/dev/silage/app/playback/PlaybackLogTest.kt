package dev.silage.app.playback

import dev.silage.app.XdgPaths
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLogTest {
    private val decision = SiloJson.decodeFromString(
        PlaybackDecisionResponseV3.serializer(),
        """{"protocol_version":3,"session_id":"s1","playback_plan":{"plan_id":"p1","delivery":"original_http",
            "stream":{"url":"/api/v2/stream/77?token=SECRET123&x=1","protocol":"http_progressive","headers":{"Authorization":"Bearer SECRET456"}},
            "decision_reason":"direct",
            "subtitle":{"mode":"render","inventory":[{"track_id":"t1","combined_index":0,"source":"external","language":"eng","delivery":"sidecar","url":"/api/v2/subs/1.srt"}]}}}""",
    )

    @Test fun describesThePlanWithoutCredentials() {
        val text = PlaybackLog.describe(decision)
        assertContains(text, "original_http")
        assertContains(text, "/api/v2/subs/1.srt")
        assertFalse("SECRET123" in text, text)
        assertFalse("SECRET456" in text, text)
    }

    @Test fun kdeDisplayConfigIsReadFromTheUserConfigDirNotSilages() {
        val p = XdgPaths.kwinOutputConfig.toString()
        assertTrue(p.endsWith("/kwinoutputconfig.json"), p)
        assertFalse("/silage/" in p, p)
    }
}
