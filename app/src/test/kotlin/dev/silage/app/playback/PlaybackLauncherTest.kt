package dev.silage.app.playback

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaybackLauncherTest {
    private fun decision(url: String, headers: String = "{}") = SiloJson.decodeFromString(
        PlaybackDecisionResponseV3.serializer(),
        """{"protocol_version":3,"session_id":"sess-1","playback_plan":{"plan_id":"p1","delivery":"original_http",
            "stream":{"url":"$url","protocol":"http_progressive","headers":$headers},"decision_reason":"direct"}}""",
    )

    private fun launcher(
        versions: List<FileVersion> = listOf(FileVersion(fileId = 77)),
        response: ApiResult<PlaybackDecisionResponseV3> = ApiResult.Success(decision("/api/v2/stream/77")),
        sent: MutableList<PlaybackStartRequestV3> = mutableListOf(),
    ) = PlaybackLauncher(
        detail = { ApiResult.Success(WatchDetail(contentId = it, type = "movie", title = "M", versions = versions)) },
        start = { req -> sent += req; response },
        profileId = { "prof-1" },
        serverUrl = { "http://nas:8096" },
    )

    @Test fun resolvesRelativeUrlAndUsesFirstVersion() = runTest {
        val sent = mutableListOf<PlaybackStartRequestV3>()
        val r = launcher(sent = sent).launch("movie-1")
        assertIs<LaunchResult.Ready>(r)
        assertEquals("http://nas:8096/api/v2/stream/77", r.url)
        assertEquals("sess-1", r.sessionId)
        assertEquals(77, sent.single().fileId)
        assertEquals("prof-1", sent.single().profileId)
    }

    @Test fun keepsAbsoluteUrlAndHeaders() = runTest {
        val r = launcher(response = ApiResult.Success(decision("https://cdn.example/x.mkv", """{"Authorization":"Bearer t"}"""))).launch("m")
        assertIs<LaunchResult.Ready>(r)
        assertEquals("https://cdn.example/x.mkv", r.url)
        assertEquals(mapOf("Authorization" to "Bearer t"), r.headers)
    }

    @Test fun noVersionsFails() = runTest {
        assertEquals(LaunchResult.Failed("This item has no playable file."), launcher(versions = emptyList()).launch("series-1"))
    }

    @Test fun serverErrorFails() = runTest {
        val r = launcher(response = ApiResult.Error(403, "forbidden", "Not allowed")).launch("m")
        assertEquals(LaunchResult.Failed("The server refused playback: Not allowed"), r)
    }

    @Test fun advertisesDesktopCapabilities() = runTest {
        val sent = mutableListOf<PlaybackStartRequestV3>()
        launcher(sent = sent).launch("m")
        val caps = sent.single().capabilities
        assertEquals(true, "hevc" in caps.codecsVideo && "av1" in caps.codecsVideo)
        assertEquals("tv", sent.single().clientPlaybackContext.formFactor)
    }
}
