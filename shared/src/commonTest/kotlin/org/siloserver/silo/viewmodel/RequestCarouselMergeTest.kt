package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.model.request.RequestMediaResult
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors silo-apple's RequestCarouselMerge — Apple is authoritative. */
class RequestCarouselMergeTest {

    private fun result(id: Int, type: String) = RequestMediaResult(
        mediaType = type,
        tmdbId = id,
        title = "T$id",
    )

    private fun section(key: String, vararg results: RequestMediaResult) =
        RequestDiscoverySection(key = key, title = key, results = results.toList())

    @Test
    fun mergesTrendingAndPopularIntoTwoInterleavedCarousels() {
        val merged = mergeDiscoverCarousels(
            listOf(
                section("trending_movies", result(1, "movie"), result(2, "movie")),
                section("trending_series", result(3, "series")),
                section("popular_movies", result(4, "movie")),
                section("popular_series", result(5, "series")),
                // iOS ignores anything beyond trending_*/popular_*.
                section("upcoming", result(6, "movie")),
            ),
        )
        assertEquals(listOf("trending", "popular"), merged.map { it.key })
        assertEquals("Trending now", merged[0].title)
        assertEquals("Crowd favorites", merged[1].title)
        // Round-robin interleave: movie, series, movie.
        assertEquals(listOf(1, 3, 2), merged[0].results.map { it.tmdbId })
        assertEquals(listOf(4, 5), merged[1].results.map { it.tmdbId })
    }

    @Test
    fun dedupesAcrossMoviesAndSeriesWithinACarousel() {
        val merged = mergeDiscoverCarousels(
            listOf(
                section("trending_movies", result(1, "movie"), result(1, "movie")),
                section("trending_series", result(1, "series")),
            ),
        )
        // Same tmdbId with different media types are distinct items;
        // duplicate (movie, 1) collapses.
        assertEquals(2, merged.single().results.size)
    }

    @Test
    fun emptyPoolsProduceNoCarousels() {
        assertEquals(emptyList(), mergeDiscoverCarousels(emptyList()))
        assertEquals(
            listOf("popular"),
            mergeDiscoverCarousels(
                listOf(section("popular_movies", result(1, "movie"))),
            ).map { it.key },
        )
    }
}
