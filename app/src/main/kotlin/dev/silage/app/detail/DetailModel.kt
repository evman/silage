package dev.silage.app.detail

import dev.silage.app.browse.formatRuntime
import dev.silage.app.playback.hud.formatTime
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData

data class CastCard(val name: String, val role: String?, val photoUrl: String?, val photoThumbhash: String?)

data class DetailModel(
    val title: String,
    val logoUrl: String?,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
    val metaLine: String,
    val tagline: String?,
    val overview: String?,
    val playLabel: String,
    val canPlayFromStart: Boolean,
    val cast: List<CastCard>,
    val isSeries: Boolean,
)

data class EpisodeRow(
    val id: String,
    val number: String,
    val title: String,
    val overview: String?,
    val stillUrl: String?,
    val stillThumbhash: String?,
    val runtime: String?,
    val progress: Float?,
    val watched: Boolean,
)

/** Saved position worth resuming from: in progress and not finished. */
fun LeafItemUserData?.resumePosition(): Double? =
    this?.takeIf { !it.played }?.positionSeconds?.takeIf { it > 0 && (isInProgress != false) }

private fun LeafItemUserData?.progress(): Float? {
    val pos = this?.positionSeconds ?: return null
    val dur = durationSeconds ?: return null
    return if (pos > 0 && dur > 0 && !played) (pos / dur).coerceIn(0.0, 1.0).toFloat() else null
}

fun ItemDetail.toDetailModel(): DetailModel {
    val isSeries = type == "series" || type == "show"
    val resume = userData.resumePosition()
    return DetailModel(
        title = title,
        logoUrl = logoUrl,
        backdropUrl = backdropUrl,
        backdropThumbhash = backdropThumbhash,
        metaLine = listOfNotNull(
            year.takeIf { it > 0 }?.toString(),
            if (isSeries) seasonCount?.let { if (it == 1) "1 season" else "$it seasons" } else formatRuntime(runtime),
            contentRating,
            genres.take(3).joinToString(", ").ifBlank { null },
        ).joinToString(" · "),
        tagline = tagline?.takeIf { it.isNotBlank() },
        overview = overview?.takeIf { it.isNotBlank() },
        playLabel = if (resume != null) "Resume from ${formatTime(resume)}" else "Play",
        canPlayFromStart = resume != null,
        cast = cast.sortedBy { it.order }.take(20).map { CastCard(it.name, it.character, it.photoUrl, it.photoThumbhash) },
        isSeries = isSeries,
    )
}

fun EpisodeListItem.toRow(): EpisodeRow = EpisodeRow(
    id = contentId,
    number = episodeNumber.toString(),
    title = title?.takeIf { it.isNotBlank() } ?: "Episode $episodeNumber",
    overview = overview?.takeIf { it.isNotBlank() },
    stillUrl = stillUrl,
    stillThumbhash = stillThumbhash,
    runtime = formatRuntime(runtime),
    progress = userData.progress(),
    watched = userData?.played == true,
)

/** The episode a series' Play button starts: in progress, else the first unwatched, else the first. */
fun nextEpisode(episodes: List<EpisodeListItem>): EpisodeListItem? =
    episodes.firstOrNull { it.userData?.isInProgress == true && it.userData?.played != true }
        ?: episodes.firstOrNull { it.userData?.played != true }
        ?: episodes.firstOrNull()
