package dev.silage.app.requests

import dev.silage.app.browse.CardModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.libraryItemToOpen
import org.siloserver.silo.model.request.requestBackdropUrl
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.network.ApiResult

/** Where a request card leads: a title in the library, or a request page. */
sealed interface RequestTarget {
    data class Library(val contentId: String) : RequestTarget
    data class Request(val mediaType: String, val tmdbId: Int) : RequestTarget
}

private const val CardPrefix = "request:"

/** Card ids for requestable titles, so they can share grids and rows with library cards. */
fun requestCardId(mediaType: String, tmdbId: Int): String = "$CardPrefix$mediaType:$tmdbId"

/** The target a card made by [toRequestCard] opens, or null for an ordinary library card. */
fun requestTargetOf(card: CardModel): RequestTarget? {
    if (!card.id.startsWith(CardPrefix)) return null
    if (card.openTarget != card.id) return RequestTarget.Library(card.openTarget)
    val rest = card.id.removePrefix(CardPrefix)
    val type = rest.substringBefore(':')
    val id = rest.substringAfter(':').toIntOrNull() ?: return null
    return RequestTarget.Request(type, id)
}

private fun kindLabel(mediaType: String) = if (mediaType == RequestMediaType.Series) "Series" else "Movie"

private fun requestCard(
    mediaType: String, tmdbId: Int, title: String, year: Int?, overview: String, posterPath: String?, backdropPath: String?,
    status: String?, libraryContentId: String?,
): CardModel {
    val id = requestCardId(mediaType, tmdbId)
    return CardModel(
        id = id,
        title = title,
        caption = listOfNotNull(year?.toString(), status ?: kindLabel(mediaType)).joinToString(" · "),
        imageUrl = requestPosterUrl(posterPath),
        thumbhash = null,
        landscape = false,
        progress = null,
        watched = false,
        backdropUrl = requestBackdropUrl(backdropPath),
        backdropThumbhash = null,
        logoUrl = null,
        overview = overview.takeIf { it.isNotBlank() },
        metaLine = listOfNotNull(year?.toString(), kindLabel(mediaType), status).joinToString(" · "),
        openTarget = libraryContentId ?: id,
    )
}

/** A search or discover result as a card; its caption carries the request status when there is one. */
fun RequestMediaResult.toRequestCard(): CardModel = requestCard(
    mediaType, tmdbId, title, year, overview, posterPath, backdropPath,
    status = RequestProgress.of(availability, request)?.shortLabel,
    libraryContentId = libraryItemToOpen(),
)

/** One of the user's own requests as a card. */
fun MediaRequest.toRequestCard(): CardModel = requestCard(
    mediaType, tmdbId, title, year, overview, posterPath, backdropPath,
    status = RequestProgress.of(this).shortLabel,
    libraryContentId = libraryItemToOpen(),
)

/**
 * Results worth offering under a library search: movies and series that
 * aren't simply in the library already (those show up in the library results).
 */
fun requestableResults(results: List<RequestMediaResult>): List<RequestMediaResult> = results.filter {
    (it.mediaType == RequestMediaType.Movie || it.mediaType == RequestMediaType.Series) && it.libraryItemToOpen() == null
}

/**
 * Searches the server's request catalog as the user types, a moment after
 * they stop. Re-runs when a request changes, so statuses stay current.
 */
class RequestSearch(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> ApiResult<List<RequestMediaResult>>,
    updates: kotlinx.coroutines.flow.Flow<Any?>? = null,
    private val debounceMs: Long = 450,
) {
    private val _results = MutableStateFlow<List<RequestMediaResult>>(emptyList())
    val results: StateFlow<List<RequestMediaResult>> = _results.asStateFlow()
    private var job: Job? = null
    private var query = ""

    init {
        updates?.let { flow -> scope.launch { flow.drop(1).filterNotNull().collect { run(query, wait = false) } } }
    }

    fun onInput(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        run(trimmed, wait = true)
    }

    private fun run(text: String, wait: Boolean) {
        job?.cancel()
        if (text.isBlank()) { _results.value = emptyList(); return }
        if (wait) _results.value = emptyList()
        job = scope.launch {
            if (wait) delay(debounceMs)
            val result = search(text)
            if (text != query) return@launch
            if (result is ApiResult.Success) _results.value = requestableResults(result.data)
        }
    }
}
