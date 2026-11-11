package dev.silage.app.playback

import org.siloserver.silo.model.catalog.ItemDetail

/**
 * Posters for what's playing, best first, for the system's media controls and Discord.
 * An episode gets its season's or series' poster, never an episode still (they crop badly
 * to a square). Only absolute http(s) links: the overlays load them themselves.
 */
internal fun posterCandidates(detail: ItemDetail): List<String> =
    listOfNotNull(
        detail.seasonPosterUrl, detail.seriesPosterUrl,
        detail.posterUrl.takeIf { detail.posterIsEpisodeStill != true },
    ).filter(::isWebLink).distinct()

internal fun isWebLink(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/**
 * The posters for [meta]: its own artwork when the playback details carried one, else from the
 * title's full details ([detail]), which is where current servers keep it. Empty when there are none.
 */
internal suspend fun playbackPosters(meta: PlaybackMeta, detail: suspend (contentId: String) -> ItemDetail?): List<String> {
    val own = listOfNotNull(meta.artworkUrl?.takeIf(::isWebLink))
    if (own.isNotEmpty() || meta.contentId == null) return own
    return runCatching { detail(meta.contentId) }.getOrNull()?.let(::posterCandidates).orEmpty()
}
