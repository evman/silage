package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.model.request.RequestMediaResult

/**
 * Merges the server's fixed per-media-type discover sections
 * (`trending_movies` / `trending_series` / `popular_movies` /
 * `popular_series`) into the two carousels the hub actually shows —
 * "Trending now" and "Crowd favorites" — with movies and series round-robin
 * interleaved and deduped. Mirrors silo-apple's `RequestCarouselMerge`
 * (Apple is authoritative); any other server section (upcoming / on-air) is
 * intentionally dropped, matching iOS.
 */
fun mergeDiscoverCarousels(
    sections: List<RequestDiscoverySection>,
): List<RequestDiscoverySection> {
    fun merged(key: String, title: String, movieKey: String, seriesKey: String): RequestDiscoverySection? {
        val movies = sections.firstOrNull { it.key == movieKey }?.results.orEmpty()
        val series = sections.firstOrNull { it.key == seriesKey }?.results.orEmpty()
        val interleaved = interleave(movies, series)
        if (interleaved.isEmpty()) return null
        return RequestDiscoverySection(
            key = key,
            title = title,
            totalResults = interleaved.size,
            results = interleaved,
        )
    }
    return listOfNotNull(
        merged("trending", "Trending now", "trending_movies", "trending_series"),
        merged("popular", "Crowd favorites", "popular_movies", "popular_series"),
    )
}

private fun interleave(
    first: List<RequestMediaResult>,
    second: List<RequestMediaResult>,
): List<RequestMediaResult> {
    val out = mutableListOf<RequestMediaResult>()
    val seen = mutableSetOf<Pair<String, Int>>()
    fun add(item: RequestMediaResult) {
        if (seen.add(item.mediaType to item.tmdbId)) out += item
    }
    val max = maxOf(first.size, second.size)
    for (index in 0 until max) {
        first.getOrNull(index)?.let(::add)
        second.getOrNull(index)?.let(::add)
    }
    return out
}
