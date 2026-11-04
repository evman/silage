package dev.silage.app.browse

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.CatalogQueryV2
import org.siloserver.silo.network.apiv2.CatalogV2Api
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LetterJumpTest {
    private val bodies = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val client = HttpClient(MockEngine { request ->
        val body = when (val b = request.body) { is TextContent -> b.text; else -> b.toString() }
        bodies += request.url.encodedPath to Json.parseToJsonElement(body).jsonObject
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        if (request.url.encodedPath.endsWith("/letters")) respond("""{"total":9,"letters":[{"letter":"M","count":4,"position":5}],"runs":[]}""", HttpStatusCode.OK, json)
        else respond("""{"items":[],"total":9,"total_exact":true,"window_cursor":"w","page":{"has_more":${bodies.size == 1},"next_cursor":${if (bodies.size == 1) "\"c2\"" else "null"}}}""", HttpStatusCode.OK, json)
    }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) } }
    private val api = CatalogV2Api(client, ApiV2Gate.Unrestricted)

    @Test fun aJumpSeeksOnceThenFollowsTheCursor() = runBlocking {
        val query = CatalogQueryV2(libraryId = "7", sort = "title", order = "asc", limit = 60, seek = 318)
        val first = api.browse(query) as ApiResult.Success
        val again = api.browse(query, first.data.continuation)
        assertTrue(again is ApiResult.Success, "$again")
        assertEquals("/api/v2/catalog/query", bodies[0].first)
        assertEquals("318", bodies[0].second["seek"].toString())
        assertFalse("cursor" in bodies[0].second)
        assertFalse("seek" in bodies[1].second)
        assertEquals("\"c2\"", bodies[1].second["cursor"].toString())
    }

    @Test fun lettersAskForTheSameQuery() = runBlocking {
        val result = api.letters("7", emptyList(), null, "desc") as ApiResult.Success
        assertEquals(5, result.data.single().position)
        assertEquals("/api/v2/catalog/letters", bodies[0].first)
        assertEquals(setOf("library_id", "order"), bodies[0].second.keys)
    }
}
