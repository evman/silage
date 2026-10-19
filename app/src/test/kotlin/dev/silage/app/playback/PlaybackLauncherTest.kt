package dev.silage.app.playback

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
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

    @Test fun explicitAudioChoiceAndPositionReachServer() = runTest {
        val sent = mutableListOf<PlaybackStartRequestV3>()
        val r = launcher(sent = sent).launch("movie-1", audioTrackIndex = 2, positionSeconds = 42.0)
        assertIs<LaunchResult.Ready>(r)
        assertEquals(2, sent.single().audioTrackIndex)
        assertEquals(42.0, sent.single().startPosition)
        assertEquals(42.0, r.startSeconds)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun packagedAudioReplanUsesServerPlayerStartNotSourcePosition() = runTest {
        val response = SiloJson.decodeFromString(PlaybackDecisionResponseV3.serializer(),
            """{"protocol_version":3,"session_id":"sess-1","playback_plan":{"plan_id":"p1","delivery":"server_transcode_hls",
            "stream":{"url":"/stream","protocol":"hls","headers":{}},"decision_reason":"audio",
            "timeline":{"source_start_seconds":42.0,"stream_origin_seconds":42.0,"player_start_seconds":0.0,"timeline_offset_seconds":42.0}}}""")
        val ready = launcher(response = ApiResult.Success(response)).launch("movie-1", audioTrackIndex = 1, positionSeconds = 42.0)
        assertIs<LaunchResult.Ready>(ready)
        assertEquals(0.0, ready.startSeconds, "Replanned stream starts at source 42: seeking local 42 skips ahead twice")
        val player = FakePlayer()
        val progress = mutableListOf<Double>()
        val job = launch { PlaybackController(player, { _, pos, _ -> progress += pos }, {}).run(ready) }
        try {
            runCurrent()
            player.state.value = dev.silage.player.PlayerState(positionSeconds = 5.0, paused = true, loaded = true)
            runCurrent()
            assertEquals(listOf(47.0), progress, "Progress after the audio replan must remain on the source clock")
        } finally { job.cancel(); job.join() }
    }

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
                seriesId = "series-9", seriesTitle = "Severance", seasonNumber = 1, episodeNumber = 3,
                intro = TimeRange(30.0, 95.0), credits = TimeRange(2900.0, 3000.0))
        }).launch("ep-1")
        assertIs<LaunchResult.Ready>(r)
        // The series and episode numbers ride along so the player can find the next episode.
        assertEquals(PlaybackMeta("Pilot", "Severance · S1 E3", TimeRange(30.0, 95.0), TimeRange(2900.0, 3000.0),
            seriesId = "series-9", seriesTitle = "Severance", seasonNumber = 1, episodeNumber = 3), r.meta)
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

    @Test fun inProgressItemResumesAndFromStartDoesNot() = runTest {
        val progress = org.siloserver.silo.model.catalog.LeafItemUserData(played = false, isInProgress = true, positionSeconds = 754.0, durationSeconds = 6000.0)
        val l = launcher(item = { WatchDetail(contentId = it, type = "movie", title = "M", versions = listOf(FileVersion(fileId = 1)), userData = progress) })
        assertEquals(754.0, (l.launch("m") as LaunchResult.Ready).startSeconds)
        assertEquals(null, (l.launch("m", fromStart = true) as LaunchResult.Ready).startSeconds)
    }

    @Test fun playsTheChosenCopy() = runTest {
        val sent = mutableListOf<PlaybackStartRequestV3>()
        launcher(versions = listOf(FileVersion(fileId = 1), FileVersion(fileId = 77)), sent = sent).launch("m", fileId = 77)
        assertEquals(77, sent.single().fileId)
    }

    @Test fun aChosenCopyThatNoLongerExistsFallsBackToTheFirst() = runTest {
        val sent = mutableListOf<PlaybackStartRequestV3>()
        launcher(versions = listOf(FileVersion(fileId = 1), FileVersion(fileId = 77)), sent = sent).launch("m", fileId = 5)
        assertEquals(1, sent.single().fileId)
    }

    @Test fun withoutAChoiceTheBestCopyPlays() = runTest {
        val sent = mutableListOf<PlaybackStartRequestV3>()
        val copies = listOf(FileVersion(fileId = 1, resolution = "1080p"), FileVersion(fileId = 2, resolution = "2160p", hdr = true), FileVersion(fileId = 3, resolution = "2160p"))
        val r = launcher(versions = copies, sent = sent).launch("m")
        assertEquals(2, sent.single().fileId)
        assertEquals("4K · HDR · original file", (r as LaunchResult.Ready).versionLabel)
    }

    @Test fun lastPlayedCopyOnlyUnderThatSetting() {
        val copies = listOf(FileVersion(fileId = 1, resolution = "1080p"), FileVersion(fileId = 2, resolution = "2160p"))
        assertEquals(2, defaultCopy(copies, lastFileId = 1, preferLastPlayed = false)?.fileId)
        assertEquals(1, defaultCopy(copies, lastFileId = 1, preferLastPlayed = true)?.fileId)
        assertEquals(2, defaultCopy(copies, lastFileId = 99, preferLastPlayed = true)?.fileId)
        assertEquals(null, defaultCopy(emptyList(), null, false))
    }

    private fun hls(url: String) = SiloJson.decodeFromString(PlaybackDecisionResponseV3.serializer(),
        """{"protocol_version":3,"session_id":"s","playback_plan":{"plan_id":"p","delivery":"server_remux_hls",
        "stream":{"url":"$url","protocol":"hls","headers":{}},"decision_reason":"remux"}}""")

    private fun authLauncher(response: PlaybackDecisionResponseV3) = PlaybackLauncher(
        detail = { ApiResult.Success(WatchDetail(contentId = it, type = "movie", title = "M", versions = listOf(FileVersion(fileId = 1)))) },
        start = { ApiResult.Success(response) },
        profileId = { "prof-1" },
        serverUrl = { "https://silo.example:443" },
        mediaAuth = { mapOf("Authorization" to "Bearer TOKEN", "X-Profile-Id" to "prof-1") },
    )

    /** Silo's converted streams refuse a playlist request without the sign-in headers (HTTP 401). */
    @Test fun convertedStreamsFromTheServerCarryTheSignInHeaders() = runTest {
        val r = authLauncher(hls("/api/v2/playback/transcode/x/master.m3u8?st=abc")).launch("m")
        assertIs<LaunchResult.Ready>(r)
        assertEquals("Bearer TOKEN", r.headers["Authorization"])
        assertEquals("prof-1", r.headers["X-Profile-Id"])
        assertTrue(r.needsMediaAuth)
    }

    @Test fun signInHeadersStayOffOriginalFilesAndOtherHosts() = runTest {
        val direct = authLauncher(decision("/api/v2/stream/1?st=abc")).launch("m")
        assertIs<LaunchResult.Ready>(direct)
        assertTrue("Authorization" !in direct.headers)
        val elsewhere = authLauncher(hls("https://cdn.example/master.m3u8")).launch("m")
        assertIs<LaunchResult.Ready>(elsewhere)
        assertTrue("Authorization" !in elsewhere.headers)
        assertTrue(!elsewhere.needsMediaAuth)
    }

    @Test fun originsCompareSchemeHostAndPort() {
        assertTrue(sameOrigin("https://silo.example/a", "https://silo.example:443"))
        assertTrue(!sameOrigin("http://silo.example/a", "https://silo.example"))
        assertTrue(!sameOrigin("https://silo.example:8443/a", "https://silo.example"))
        assertTrue(!sameOrigin("not a url", "https://silo.example"))
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
