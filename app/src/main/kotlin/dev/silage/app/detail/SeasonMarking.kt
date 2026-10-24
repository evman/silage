package dev.silage.app.detail

import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.Season

/**
 * What "Mark watched up to here" marks for [episodeId] of [season]: each earlier regular season
 * as a whole (the server marks all of its episodes), then this season's episodes up to and
 * including that one that aren't watched yet. Specials are left alone.
 */
fun upToHereTargets(seasons: List<Season>, season: Int?, episodes: List<EpisodeListItem>, episodeId: String): List<String> {
    val index = episodes.indexOfFirst { it.contentId == episodeId }
    if (index < 0) return emptyList()
    val earlier = if (season != null && season > 0) {
        seasons.filter { !it.isSpecials && it.seasonNumber in 1 until season }.sortedBy { it.seasonNumber }.map { it.contentId }
    } else emptyList()
    return earlier + episodes.take(index + 1).filter { it.userData?.played != true }.map { it.contentId }
}

/** Whether every loaded episode of the season is watched, so its button offers to unmark it. */
fun seasonAllWatched(episodes: List<EpisodeListItem>): Boolean = episodes.isNotEmpty() && episodes.all { it.userData?.played == true }
