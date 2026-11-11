package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestState
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.DefaultRequestsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `PUT`/`DELETE /api/v2/requests/follows/{media_type}/{tmdb_id}` and the fields that gate it. */
class RequestFollowV2Test {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun followIsOnePutAndDecodesTheTitleState() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/v2/requests/follows/series/1399", request.url.encodedPath)
            respond(
                """{"status":"approved","state":"approved","requestable":false,"reason":"already_requested","following":true,"requested_by_viewer":false}""",
                headers = json,
            )
        })
        val result = DefaultRequestsApi(client, ApiV2Gate.Unrestricted).follow("series", 1399)
        val state = assertIs<ApiResult.Success<RequestState>>(result).data
        assertTrue(state.following)
        assertFalse(state.requestedByViewer)
        assertEquals("already_requested", state.reason)
        assertNull(state.requestId)
        assertEquals(1, calls)
        client.close()
    }

    @Test fun unfollowIsOneDeleteAnsweredWithNoContent() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/api/v2/requests/follows/movie/42", request.url.encodedPath)
            respond("", HttpStatusCode.NoContent)
        })
        assertIs<ApiResult.Success<Unit>>(DefaultRequestsApi(client, ApiV2Gate.Unrestricted).unfollow("movie", 42))
        assertEquals(1, calls)
        client.close()
    }

    @Test fun aDisabledFeatureSurfacesItsProblemCode() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                """{"type":"https://siloserver.org/docs/api/v2/problems/capability_disabled","title":"Capability disabled","status":409,"detail":"The requests capability is disabled by an administrator."}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        })
        val error = assertIs<ApiResult.Error>(DefaultRequestsApi(client, ApiV2Gate.Unrestricted).follow("movie", 42))
        assertEquals("capability_disabled", error.error)
        client.close()
    }

    @Test fun titleStateDecodesFollowFieldsAndDefaultsThemOnOlderServers() {
        val current = SiloJson.decodeFromString<RequestMediaResult>(
            """{"media_type":"movie","tmdb_id":7,"title":"Film","request":{"status":"pending","requestable":false,"reason":"already_requested","following":true,"requested_by_viewer":true}}""",
        )
        assertTrue(current.request.following)
        assertTrue(current.request.requestedByViewer)

        val older = SiloJson.decodeFromString<RequestMediaResult>(
            """{"media_type":"movie","tmdb_id":7,"title":"Film","request":{"status":"pending","requestable":false,"reason":"already_requested"}}""",
        )
        assertFalse(older.request.following)
        assertFalse(older.request.requestedByViewer)
    }

    @Test fun statusDecodesFollowSupported() {
        val current = SiloJson.decodeFromString<RequestsFeatureStatus>(
            """{"requests_enabled":true,"allowed":true,"state":"available","follow_supported":true}""",
        )
        assertTrue(current.followSupported)
        val older = SiloJson.decodeFromString<RequestsFeatureStatus>("""{"requests_enabled":true,"allowed":true,"state":"available"}""")
        assertFalse(older.followSupported)
    }
}
