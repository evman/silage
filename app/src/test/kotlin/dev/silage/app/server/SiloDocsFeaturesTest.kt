package dev.silage.app.server

import dev.silage.app.detail.nextRating
import dev.silage.app.home.tasteList
import dev.silage.app.playback.hud.BufferingGuard
import dev.silage.app.profiles.ProfileDraft
import dev.silage.app.profiles.draftProblem
import dev.silage.app.profiles.patchFor
import dev.silage.app.settings.moved
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.Patch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiloDocsFeaturesTest {
    private fun row(id: String, title: String = id, hidden: Boolean = false, position: Int = 0) = HomeRow(id, title, defaultTitle = id, hidden = hidden, position = position)
    private fun overrides(rows: List<HomeRow>, baseline: List<HomeRow>, saved: List<SavedHomeRow> = emptyList()) =
        homeRowOverrides(rows, baseline, saved) { "new" }["overrides"]!!.jsonArray.map { it.jsonObject }

    @Test fun aRowLeftAloneStoresNothing() {
        val base = listOf(row("a"), row("b"))
        assertTrue(overrides(base, base).isEmpty())
    }

    @Test fun hidingStoresOnlyThatRowWithoutAPosition() {
        val base = listOf(row("a"), row("b"))
        val one = overrides(listOf(row("a", hidden = true), row("b")), base).single()
        assertEquals("a", one["section_id"]!!.jsonPrimitive.content)
        assertEquals("true", one["hidden"]!!.jsonPrimitive.content)
        assertFalse("position" in one)
    }

    @Test fun movingARowOrdersThemAll() {
        val base = listOf(row("a"), row("b"))
        val all = overrides(moved(base, 1, -1), base)
        assertEquals(listOf("b" to "0", "a" to "1"), all.map { it["section_id"]!!.jsonPrimitive.content to it["position"]!!.jsonPrimitive.content })
    }

    @Test fun aSavedIdIsKeptAndARemovedRowStaysRemoved() {
        val base = listOf(row("a"))
        val saved = listOf(SavedHomeRow(id = "kept", sectionId = "a", hidden = true), SavedHomeRow(id = "gone", sectionId = "z", removed = true))
        val all = overrides(listOf(row("a", hidden = false)), base, saved)
        assertEquals("kept", all[0]["id"]!!.jsonPrimitive.content)
        assertEquals("z", all[1]["section_id"]!!.jsonPrimitive.content)
        assertEquals("true", all[1]["removed"]!!.jsonPrimitive.content)
    }

    @Test fun renamingBackToTheOriginalClearsIt() {
        val base = listOf(row("a", title = "Mine"))
        assertFalse("title" in overrides(listOf(row("a", title = "a")), base, listOf(SavedHomeRow(id = "x", sectionId = "a", title = "Mine"))).single())
        assertEquals("New", overrides(listOf(row("a", title = "New")), base).single()["title"]!!.jsonPrimitive.content)
    }

    @Test fun movingPastAnEndChangesNothing() {
        assertEquals(listOf(1, 2), moved(listOf(1, 2), 0, -1))
        assertEquals(listOf(1, 2), moved(listOf(1, 2), 1, 1))
    }

    @Test fun subtitleFilesAreCheckedBeforeSending() {
        assertNull(subtitleFileProblem("Film.en.SRT", 100))
        assertTrue(subtitleFileProblem("Film.txt", 100) != null)
        assertTrue(subtitleFileProblem("Film.srt", SubtitleUploadLimit + 1) != null)
        assertTrue(looksHearingImpaired("Film.en.sdh.srt"))
        assertFalse(looksHearingImpaired("Chihiro.srt"))
    }

    @Test fun uploadSendsAFormAndReadsTheStoredId() = runBlocking {
        var sent: OutgoingContent? = null
        var path = ""
        val client = HttpClient(MockEngine { request ->
            sent = request.body; path = request.url.encodedPath
            assertEquals(HttpMethod.Post, request.method)
            respond("""{"subtitle":{"id":"77"}}""", HttpStatusCode.OK)
        })
        val tokens = TokenManagerImpl().apply { }
        val extras = SiloExtras(client, object : org.siloserver.silo.network.TokenManager by tokens {
            override suspend fun snapshotCurrentScope() = AuthScopeSnapshot("server", "profile", "https://silo.example", null)
        })
        assertEquals(77, extras.uploadSubtitle(42, "Film.srt", byteArrayOf(1)).getOrThrow())
        assertEquals("/api/v2/subtitles/upload", path)
        assertTrue(sent is OutgoingContent.WriteChannelContent || sent is OutgoingContent.ReadChannelContent || sent is OutgoingContent.ByteArrayContent)
    }

    @Test fun aServerProblemReadsAsItsDetail() {
        assertEquals("Subtitle file must be under 5 MB.", problemMessage(413, """{"detail":"Subtitle file must be under 5 MB."}"""))
        assertEquals("The server said 500.", problemMessage(500, "oops"))
    }

    @Test fun choosingTheSameStarsClearsTheRating() {
        assertEquals(3, nextRating(null, 3))
        assertEquals(5, nextRating(3, 5))
        assertNull(nextRating(3, 3))
    }

    @Test fun tasteReadsNaturally() {
        assertEquals("Drama", tasteList(listOf("Drama")))
        assertEquals("Drama, Comedy and War", tasteList(listOf("Drama", "Comedy", "War")))
    }

    // ---- Buffering

    @Test fun threeRealStopsTrigger() {
        val g = BufferingGuard()
        var t = 100_000L
        fun stop(): Boolean { var fired = g.update(true, true, t); t += 2_100; fired = g.update(true, true, t) || fired; g.update(false, true, t); t += 10_000; return fired }
        assertFalse(stop()); assertFalse(stop()); assertTrue(stop())
        // And then it leaves things alone for a while.
        assertFalse(stop())
    }

    @Test fun shortStopsPausesAndSeeksDontCount() {
        val g = BufferingGuard()
        var t = 100_000L
        repeat(5) { g.update(true, true, t); t += 1_000; assertFalse(g.update(false, true, t)); t += 1_000 }
        repeat(5) { g.update(true, false, t); t += 3_000; assertFalse(g.update(true, false, t)); t += 1_000 }
        repeat(5) { g.seeked(t); g.update(true, true, t); t += 3_000; assertFalse(g.update(true, true, t)); g.update(false, true, t); t += 6_000 }
    }

    @Test fun theFirstFillDoesntCount() {
        val g = BufferingGuard()
        g.started(100_000)
        g.update(true, true, 100_000)
        g.update(true, true, 103_000)
        g.update(false, true, 103_000)
        var t = 130_000L
        repeat(2) { g.update(true, true, t); t += 2_100; assertFalse(g.update(true, true, t)); g.update(false, true, t); t += 5_000 }
    }

    @Test fun stopsSpreadOutDontAddUp() {
        val g = BufferingGuard()
        var t = 100_000L
        repeat(6) { g.update(true, true, t); t += 2_100; assertFalse(g.update(true, true, t)); g.update(false, true, t); t += 200_000 }
    }


    // ---- Profiles

    private val before = ProfileDraft(name = "Sam", maxRating = "PG", restricted = true, libraries = setOf(1, 2))

    @Test fun onlyChangesAreSent() {
        val patch = patchFor(before, before)
        assertEquals(Patch.Omit, patch.name)
        assertEquals(Patch.Omit, patch.pin)
        assertEquals(Patch.Omit, patch.maxContentRating)
        assertEquals(Patch.Omit, patch.allowedLibraryIds)
        assertEquals(0, patch.toJsonObject().size)
    }

    @Test fun clearingALimitOrPinSaysNull() {
        val json = patchFor(before, before.copy(maxRating = null, removePin = true)).toJsonObject()
        assertEquals(kotlinx.serialization.json.JsonNull, json["max_content_rating"])
        assertEquals(kotlinx.serialization.json.JsonNull, json["pin"])
    }

    @Test fun libraryIdsGoAsStrings() {
        val json = patchFor(before, before.copy(libraries = setOf(3))).toJsonObject()
        assertEquals(listOf("3"), json["allowed_library_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun aDraftIsCheckedBeforeSaving() {
        val others = listOf(Profile(id = "1", name = "Alex"))
        assertNull(draftProblem(ProfileDraft(name = "Sam"), others, null))
        assertTrue(draftProblem(ProfileDraft(name = "  "), others, null) != null)
        assertTrue(draftProblem(ProfileDraft(name = "alex"), others, null) != null)
        assertNull(draftProblem(ProfileDraft(name = "Alex"), others, "1"))
        assertTrue(draftProblem(ProfileDraft(name = "Sam", pin = "12"), others, null) != null)
        assertTrue(draftProblem(ProfileDraft(name = "Sam", restricted = true), others, null) != null)
    }
}
