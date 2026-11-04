package dev.silage.app.server

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.authScope
import java.util.UUID

/** A row of Home as this profile has it (`GET /api/v2/profile/sections/settings`). */
@Serializable
data class HomeRow(
    val id: String,
    val title: String,
    @SerialName("default_title") val defaultTitle: String = "",
    @SerialName("section_type") val sectionType: String = "",
    val hidden: Boolean = false,
    @SerialName("is_custom") val isCustom: Boolean = false,
    val position: Int = 0,
    val featured: Boolean = false,
    @SerialName("item_limit") val itemLimit: Int? = null,
    val config: JsonElement? = null,
)

/** What this profile has saved for a row (`GET /api/v2/profile/sections`). */
@Serializable
data class SavedHomeRow(
    val id: String = "",
    @SerialName("section_id") val sectionId: String = "",
    val position: Int? = null,
    val hidden: Boolean = false,
    val removed: Boolean = false,
    val title: String = "",
    val featured: Boolean? = null,
    @SerialName("item_limit") val itemLimit: Int? = null,
    val config: JsonElement? = null,
)

/**
 * The whole set of changes to save for Home, as Silo's web app builds it: a row the
 * profile left alone gets nothing, so a later change by the server's admin still reaches it.
 * Positions are stored only once the profile has ordered the rows itself.
 */
internal fun homeRowOverrides(rows: List<HomeRow>, baseline: List<HomeRow>, saved: List<SavedHomeRow>, newId: () -> String = { UUID.randomUUID().toString() }): JsonObject {
    val savedBySection = saved.filter { it.sectionId.isNotEmpty() }.associateBy { it.sectionId }
    val base = baseline.associateBy { it.id }
    val shared = rows.map { it.id }.filter { id -> id in base }
    val ordered = saved.any { it.sectionId.isNotEmpty() && it.position != null } || shared != baseline.map { it.id }.filter { id -> rows.any { it.id == id } }
    val overrides = buildList {
        rows.forEachIndexed { index, row ->
            if (row.isCustom) {
                add(buildMap<String, JsonElement> {
                    put("id", JsonPrimitive(row.id))
                    put("position", JsonPrimitive(if (ordered) index else row.position))
                    put("hidden", JsonPrimitive(row.hidden))
                    put("title", JsonPrimitive(row.title))
                    put("featured", JsonPrimitive(row.featured))
                    row.itemLimit?.let { put("item_limit", JsonPrimitive(it)) }
                    put("section_type", JsonPrimitive(row.sectionType))
                    row.config?.let { put("config", it) }
                }.let(::JsonObject))
                return@forEachIndexed
            }
            val had = savedBySection[row.id]
            val was = base[row.id]
            val title = when {
                was == null || row.title != was.title -> row.title.takeIf { it != row.defaultTitle }
                else -> had?.title?.takeIf { it.isNotEmpty() }
            }
            if (!ordered && had == null && !row.hidden && title == null) return@forEachIndexed
            add(buildMap<String, JsonElement> {
                put("section_id", JsonPrimitive(row.id))
                put("id", JsonPrimitive(had?.id?.takeIf { it.isNotEmpty() } ?: newId()))
                if (ordered) put("position", JsonPrimitive(index))
                put("hidden", JsonPrimitive(row.hidden))
                title?.let { put("title", JsonPrimitive(it)) }
                had?.featured?.let { put("featured", JsonPrimitive(it)) }
                had?.itemLimit?.let { put("item_limit", JsonPrimitive(it)) }
                had?.config?.let { put("config", it) }
            }.let(::JsonObject))
        }
        // Rows taken off Home in the web app stay off: they aren't in the list to show here.
        saved.filter { it.removed && it.sectionId.isNotEmpty() }.forEach { gone ->
            add(JsonObject(mapOf(
                "section_id" to JsonPrimitive(gone.sectionId),
                "id" to JsonPrimitive(gone.id.ifEmpty { newId() }),
                "removed" to JsonPrimitive(true),
            )))
        }
    }
    return JsonObject(mapOf("overrides" to kotlinx.serialization.json.JsonArray(overrides)))
}

/** A title offered for "pick what you like". */
@Serializable
data class TasteSeedItem(
    @SerialName("content_id") val id: String,
    val title: String = "",
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
)

/** A page of them, and the cursor of the next. */
data class TasteSeedPage(val items: List<TasteSeedItem>, val next: String?)

/** The subtitle file types Silo accepts; `.sub` isn't synced to the audio. */
internal val SubtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "sub")
internal const val SubtitleUploadLimit = 5 * 1024 * 1024L

/** Why this file can't be uploaded; null when it can. */
internal fun subtitleFileProblem(name: String, size: Long): String? = when {
    name.substringAfterLast('.', "").lowercase() !in SubtitleExtensions -> "That isn't a subtitle file (SRT, VTT, ASS, SSA or SUB)."
    size > SubtitleUploadLimit -> "That file is over the server's 5 MB limit."
    else -> null
}

/** A file named like `Film.en.sdh.srt` is marked for the hearing impaired. */
internal fun looksHearingImpaired(name: String): Boolean =
    name.lowercase().split('.', '-', '_', ' ').dropLast(1).any { it == "sdh" || it == "hi" || it == "cc" }

/** A request's reason for failing, from the server's problem report if it sent one. */
internal fun problemMessage(status: Int, body: String): String =
    runCatching { Json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "The server said $status."

/**
 * Silo's v2 calls the shared client code has no wrapper for: Home's rows, the
 * taste picker and subtitle uploads. Each is pinned to the sign-in at the time of the call.
 */
class SiloExtras(private val client: HttpClient, private val tokens: TokenManager) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Set when the picker saved, so For You knows to read its rows again. */
    @Volatile var tasteChanged = false

    private suspend fun scope(): AuthScopeSnapshot = tokens.snapshotCurrentScope() ?: error("You're signed out.")

    private suspend fun HttpResponse.text(): String {
        val body = bodyAsText()
        if (!status.isSuccess()) error(problemMessage(status.value, body))
        return body
    }

    private inline fun <reified T> items(body: String): List<T> {
        val items = json.parseToJsonElement(body).jsonObject["items"] ?: return emptyList()
        return json.decodeFromJsonElement(kotlinx.serialization.serializer<List<T>>(), items)
    }

    /** Home's rows in order, and what's saved for them. */
    suspend fun homeRows(): Result<Pair<List<HomeRow>, List<SavedHomeRow>>> = runCatching {
        val owner = scope()
        val rows = client.get("/api/v2/profile/sections/settings") { authScope(owner); parameter("scope", "home") }.text()
        val saved = client.get("/api/v2/profile/sections") { authScope(owner); parameter("scope", "home") }.text()
        items<HomeRow>(rows).sortedBy { it.position } to items<SavedHomeRow>(saved)
    }

    suspend fun saveHomeRows(rows: List<HomeRow>, baseline: List<HomeRow>, saved: List<SavedHomeRow>): Result<Unit> = runCatching {
        val owner = scope()
        client.put("/api/v2/profile/sections") {
            authScope(owner)
            parameter("scope", "home")
            contentType(ContentType.Application.Json)
            setBody(homeRowOverrides(rows, baseline, saved).toString())
        }.text()
        Unit
    }

    suspend fun resetHomeRows(): Result<Unit> = runCatching {
        val owner = scope()
        client.delete("/api/v2/profile/sections") { authScope(owner); parameter("scope", "home") }.text()
        Unit
    }

    suspend fun tasteSeedItems(cursor: String?): Result<TasteSeedPage> = runCatching {
        val owner = scope()
        val body = client.get("/api/v2/recommendations/taste-seed/items") {
            authScope(owner)
            parameter("limit", 60)
            cursor?.let { parameter("cursor", it) }
        }.text()
        val next = json.parseToJsonElement(body).jsonObject["page"]?.jsonObject?.get("next_cursor")?.jsonPrimitive?.content
        TasteSeedPage(items(body), next)
    }

    /** Each pick is recorded as a favorite. */
    suspend fun submitTasteSeed(ids: List<String>): Result<Unit> = runCatching {
        val owner = scope()
        client.post("/api/v2/recommendations/taste-seed") {
            authScope(owner)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("item_ids" to kotlinx.serialization.json.JsonArray(ids.map(::JsonPrimitive)))).toString())
        }.text()
        tasteChanged = true
    }

    /** Stores a subtitle file for [mediaFileId] and returns its id. The server works out the language and syncs it. */
    suspend fun uploadSubtitle(mediaFileId: Int, fileName: String, bytes: ByteArray): Result<Int> = runCatching {
        val owner = scope()
        val body = client.post("/api/v2/subtitles/upload") {
            authScope(owner)
            setBody(MultiPartFormDataContent(formData {
                append("media_file_id", mediaFileId.toString())
                if (looksHearingImpaired(fileName)) append("hearing_impaired", "true")
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                })
            }))
        }.text()
        json.parseToJsonElement(body).jsonObject["subtitle"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("The server didn't say which subtitle it stored.")
    }
}
