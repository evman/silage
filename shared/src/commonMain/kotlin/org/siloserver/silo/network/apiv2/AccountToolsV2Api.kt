package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.network.*

/** Hides titles from the acting profile's watch history (`removeHistoryEntries`). */
class HistoryRemovalV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    /**
     * Removes [contentId] from history: a movie, or a series card with all of its episodes
     * (the server expands a series to its episodes). Success is the server's 204.
     */
    suspend fun remove(contentId: String): ApiResult<Unit> {
        if (contentId.isBlank()) return ApiResult.Error(422, "validation_failed", "Nothing to remove.")
        val owner = tokens.captureProfileScope() ?: return identityChanged()
        return ownedV2Call<Unit, Unit>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.NoContent, { scope ->
            client.post("/api/v2/history/remove") {
                authScope(scope!!)
                requireSiloAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    putJsonArray("targets") { addJsonObject { put("content_id", contentId); put("scope", "item") } }
                })
            }
        }) { }
    }
}

/** One sign-in on the account (`listSessions`): which device, from where, and when it was last used. */
@Serializable
data class LoginSessionV2(
    val id: String,
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("ip_address") val ipAddress: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_platform") val devicePlatform: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    val current: Boolean = false,
)

@Serializable
private data class LoginSessionPageV2(val items: List<LoginSessionV2> = emptyList(), val page: PageInfo? = null)

/** The account's signed-in devices (`listSessions`) and signing one out (`deleteSession`). */
class LoginSessionsV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    /** Every live sign-in, newest first, following the pages (up to [maxPages] of 100). */
    suspend fun list(maxPages: Int = 10): ApiResult<List<LoginSessionV2>> {
        val owner = tokens.snapshotCurrentScope() ?: return identityChanged()
        val all = mutableListOf<LoginSessionV2>()
        var cursor: String? = null
        repeat(maxPages) {
            val page = ownedV2Call<LoginSessionPageV2, LoginSessionPageV2>(gate, tokens, owner, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { scope ->
                client.get("/api/v2/auth/sessions") {
                    authScope(scope!!)
                    requireSiloAuth()
                    parameter("limit", 100)
                    cursor?.let { parameter("cursor", it) }
                }
            }) { it }
            when (page) {
                is ApiResult.Success -> {
                    all += page.data.items
                    val info = page.data.page
                    cursor = if (info != null && info.hasMore) info.nextCursor else null
                    if (cursor == null) return ApiResult.Success(all.distinctBy { it.id })
                }
                is ApiResult.Error -> return page
                is ApiResult.NetworkError -> return page
            }
        }
        return ApiResult.Success(all.distinctBy { it.id })
    }

    /** Signs [id] out; it must be one of the account's own sign-ins. */
    suspend fun signOut(id: String): ApiResult<Unit> {
        if (id.isBlank()) return ApiResult.Error(422, "validation_failed", "No session given.")
        val owner = tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<Unit, Unit>(gate, tokens, owner, OwnerPolicy.IDENTITY, HttpStatusCode.NoContent, { scope ->
            client.delete("/api/v2/auth/sessions/${id.encodeURLPathPart()}") {
                authScope(scope!!)
                requireSiloAuth()
            }
        }) { }
    }
}
