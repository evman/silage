package dev.silage.app.playback

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        item: (String) -> WatchDetail = { WatchDetail(contentId = it, type = "movie", title = "M", versions = versions) },
    ) = PlaybackLauncher(
        detail = { ApiResult.Success(item(it)) },
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

    @Test fun episodeCarriesTitleLineAndMarkersForTheHud() = runTest {
        val r = launcher(item = {
            WatchDetail(contentId = it, type = "episode", title = "Pilot", versions = listOf(FileVersion(fileId = 77)),
                seriesTitle = "Severance", seasonNumber = 1, episodeNumber = 3,
                intro = TimeRange(30.0, 95.0), credits = TimeRange(2900.0, 3000.0))
        }).launch("ep-1")
        assertIs<LaunchResult.Ready>(r)
        assertEquals(PlaybackMeta("Pilot", "Severance · S1 E3", TimeRange(30.0, 95.0), TimeRange(2900.0, 3000.0)), r.meta)
    }

    @Test fun movieLineIsItsYear() = runTest {
        val r = launcher(item = { WatchDetail(contentId = it, type = "movie", title = "Dune", year = 2021, versions = listOf(FileVersion(fileId = 1))) }).launch("m")
        assertIs<LaunchResult.Ready>(r)
        assertEquals(PlaybackMeta("Dune", "2021"), r.meta)
    }

    private fun withSubs(delivery: String, selected: String?, embedded: String = "") = SiloJson.decodeFromString(
        PlaybackDecisionResponseV3.serializer(),
        """{"protocol_version":3,"session_id":"s","playback_plan":{"plan_id":"p","delivery":"$delivery",
            "stream":{"url":"/v","protocol":"hls","headers":{}},"decision_reason":"r",
            ${selected?.let { "\"selected_tracks\":{\"subtitle\":{\"id\":\"$it\"}}," } ?: ""}
            "subtitle":{"mode":"${if (selected != null) "render" else "off"}"$embedded,"inventory":[
              {"track_id":"e0","combined_index":0,"source":"embedded","language":"en","label":"SUBRIP","delivery":"sidecar","url":"/s/0.vtt?embedded_stream_index=2"},
              {"track_id":"e1","combined_index":1,"source":"embedded","language":"en","label":"SDH","hearing_impaired":true,"delivery":"sidecar","url":"/s/1.vtt?embedded_stream_index=3"},
              {"track_id":"x2","combined_index":2,"source":"external","language":"ro","delivery":"sidecar","url":"/s/2.srt"}]}}}""",
    )

    @Test fun transcodedStreamLoadsEverySubtitleFromItsLink() = runTest {
        val r = launcher(response = ApiResult.Success(withSubs("server_transcode_hls", selected = "e1"))).launch("m")
        assertIs<LaunchResult.Ready>(r)
        assertEquals(
            listOf(
                SubtitleLoad("http://nas:8096/s/0.vtt?embedded_stream_index=2", "English", "en", select = false),
                SubtitleLoad("http://nas:8096/s/1.vtt?embedded_stream_index=3", "English SDH", "en", select = true),
                SubtitleLoad("http://nas:8096/s/2.srt", "Romanian", "ro", select = false),
            ),
            r.subtitles,
        )
        assertEquals(null, r.embeddedSubtitleStream)
    }

    @Test fun directPlayKeepsBuiltInSubtitlesAndLoadsOnlyExternalOnes() = runTest {
        val r = launcher(response = ApiResult.Success(withSubs("original_http", selected = "e1",
            embedded = ""","embedded":{"stream_index":3,"container_track_id":"4"}"""))).launch("m")
        assertIs<LaunchResult.Ready>(r)
        assertEquals(listOf(SubtitleLoad("http://nas:8096/s/2.srt", "Romanian", "ro", select = false)), r.subtitles)
        assertEquals(3, r.embeddedSubtitleStream)
    }

    @Test fun subtitlesOffSelectsNothing() = runTest {
        val r = launcher(response = ApiResult.Success(withSubs("server_transcode_hls", selected = null))).launch("m")
        assertIs<LaunchResult.Ready>(r)
        assertEquals(3, r.subtitles.size)
        assertTrue(r.subtitles.none { it.select })
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

    @Test fun serverRefusalShowsItsReason() = runTest {
        val refused = SiloJson.decodeFromString(
            PlaybackDecisionResponseV3.serializer(),
            """{"protocol_version":3,"outcome":"adaptation_unavailable",
                "terminal":{"reason":"adaptation_unavailable","message":"No validated playback route is available for this source and output route."}}""",
        )
        assertEquals(
            LaunchResult.Failed("The server couldn't plan playback: No validated playback route is available for this source and output route."),
            launcher(response = ApiResult.Success(refused)).launch("m"),
        )
    }
}
