package dev.silage.app.detail

import dev.silage.app.browse.formatRuntime
import dev.silage.app.playback.hud.formatTime
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.EpisodeSpoilers

data class CastCard(val name: String, val role: String?, val photoUrl: String?, val photoThumbhash: String?, val personId: Long? = null)

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
    /** An episode the profile hasn't started, with "hide images" on: its picture is blurred. */
    val blurBackdrop: Boolean = false,
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
    /** The still is blurred (spoiler settings, unwatched episode). */
    val blurStill: Boolean = false,
)

/** Saved position worth resuming from: in progress and not finished. */
fun LeafItemUserData?.resumePosition(): Double? =
    this?.takeIf { !it.played }?.positionSeconds?.takeIf { it > 0 && (isInProgress != false) }

private fun LeafItemUserData?.progress(): Float? {
    val pos = this?.positionSeconds ?: return null
    val dur = durationSeconds ?: return null
    return if (pos > 0 && dur > 0 && !played) (pos / dur).coerceIn(0.0, 1.0).toFloat() else null
}

fun ItemDetail.toDetailModel(
    spoilers: EpisodeSpoilerPrefs = EpisodeSpoilerPrefs.NONE,
    showAdvisoryAge: Boolean = false,
): DetailModel {
    val isSeries = type == "series" || type == "show"
    val resume = userData.resumePosition()
    val unwatchedEpisode = type == "episode" && EpisodeSpoilers.isUnwatched(userData)
    return DetailModel(
        title = title,
        logoUrl = logoUrl,
        backdropUrl = backdropUrl,
        backdropThumbhash = backdropThumbhash,
        metaLine = listOfNotNull(
            year.takeIf { it > 0 }?.toString(),
            if (isSeries) seasonCount?.let { if (it == 1) "1 season" else "$it seasons" } else formatRuntime(runtime),
            contentRating,
            advisoryAge?.takeIf { showAdvisoryAge && it > 0 }?.let { "$it+" },
            genres.take(3).joinToString(", ").ifBlank { null },
        ).joinToString(" · "),
        tagline = tagline?.takeIf { it.isNotBlank() },
        overview = overview?.takeIf { it.isNotBlank() && !(unwatchedEpisode && spoilers.hidesOverview(true)) },
        playLabel = if (resume != null) "Resume from ${formatTime(resume)}" else "Play",
        canPlayFromStart = resume != null,
        cast = cast.sortedBy { it.order }.take(20).map { CastCard(it.name, it.character, it.photoUrl, it.photoThumbhash, it.personId?.toLongOrNull()) },
        isSeries = isSeries,
        blurBackdrop = unwatchedEpisode && spoilers.hidesImage(true, backdropIsEpisodeStill),
    )
}

fun EpisodeListItem.toRow(spoilers: EpisodeSpoilerPrefs = EpisodeSpoilerPrefs.NONE): EpisodeRow = EpisodeRow(
    id = contentId,
    number = episodeNumber.toString(),
    title = title?.takeIf { it.isNotBlank() } ?: "Episode $episodeNumber",
    overview = overview?.takeIf { it.isNotBlank() && !spoilers.hidesOverview(EpisodeSpoilers.isUnwatched(userData)) },
    stillUrl = stillUrl,
    stillThumbhash = stillThumbhash,
    runtime = formatRuntime(runtime),
    progress = userData.progress(),
    watched = userData?.played == true,
    blurStill = spoilers.hidesImage(EpisodeSpoilers.isUnwatched(userData), stillIsEpisodeStill),
)

/** The episode a series' Play button starts: in progress, else the first unwatched, else the first. */
fun nextEpisode(episodes: List<EpisodeListItem>): EpisodeListItem? =
    episodes.firstOrNull { it.userData?.isInProgress == true && it.userData?.played != true }
        ?: episodes.firstOrNull { it.userData?.played != true }
        ?: episodes.firstOrNull()
