package dev.silage.app.browse

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.section.SectionItem

/** One card in a row or grid, plus what the marquee shows when it is focused. */
data class CardModel(
    val id: String,
    val title: String,
    val caption: String?,
    val imageUrl: String?,
    val thumbhash: String?,
    val landscape: Boolean,
    val progress: Float?,
    val watched: Boolean,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
    val logoUrl: String?,
    val overview: String?,
    val metaLine: String,
    /** The content id to open (the detail page) or play. */
    val openTarget: String,
    /** The catalog type (movie, series, episode…), for the right-click menu; null for collections and requests. */
    val kind: String? = null,
    /** The Home row it sits in (continue_watching, next_up…), for "Remove from Continue Watching". */
    val homeRow: String? = null,
    /** The anchors a Home dismissal needs: when the progress was saved, and the episode's series. */
    val progressUpdatedAt: String? = null,
    val seriesId: String? = null,
    /** Whether the title is on the profile's watchlist (the server's user state), for the right-click menu. */
    val inWatchlist: Boolean = false,
    /** What the poster badges show (resolution, ratings…); null when the item carries none. */
    val badges: org.siloserver.silo.overlays.OverlayData? = null,
    /** Whether the title is one of the profile's favorites, for the poster's shortcut button. */
    val favorite: Boolean = false,
    /** For an episode the profile hasn't started: spoiler settings may hide its picture and description. */
    val unwatchedEpisode: Boolean = false,
    /** Whether the picture shown is a still from the episode itself; null when the server doesn't say. */
    val imageIsStill: Boolean? = null,
)

/** The picture is blurred when the profile hides unwatched episodes' pictures. */
fun CardModel.hidesImage(prefs: org.siloserver.silo.model.settings.EpisodeSpoilerPrefs): Boolean =
    unwatchedEpisode && prefs.hidesImage(true, imageIsStill)

/** The description is withheld when the profile hides unwatched episodes' descriptions. */
fun CardModel.hidesOverview(prefs: org.siloserver.silo.model.settings.EpisodeSpoilerPrefs): Boolean =
    unwatchedEpisode && prefs.hidesOverview(true)

fun isProgressRow(sectionType: String): Boolean =
    sectionType in setOf("continue_watching", "in_progress", "next_up", "up_next")

/** "2h 35m", "48m". */
fun formatRuntime(minutes: Int?): String? = minutes?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

private fun progressOf(position: Double?, duration: Double?): Float? =
    if (position != null && duration != null && position > 0 && duration > 0) (position / duration).coerceIn(0.0, 1.0).toFloat() else null

private fun episodeCaption(season: Int?, episode: Int?, title: String): String =
    listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" ").let { if (it.isBlank()) title else "$it · $title" }

private fun card(
    id: String, type: String, title: String, seriesTitle: String?, season: Int?, episode: Int?, year: Int,
    runtime: Int?, rating: String?, overview: String?, position: Double?, duration: Double?,
    posterUrl: String?, posterThumbhash: String?, backdropUrl: String?, backdropThumbhash: String?, logoUrl: String?,
    played: Boolean, landscape: Boolean, inWatchlist: Boolean = false,
    badges: org.siloserver.silo.overlays.OverlayData? = null, favorite: Boolean = false, imageIsStill: Boolean? = null,
): CardModel {
    val isEpisode = type == "episode" && seriesTitle != null
    val image = if (landscape) backdropUrl ?: posterUrl else posterUrl
    val hash = if (landscape && backdropUrl != null) backdropThumbhash else posterThumbhash
    return CardModel(
        id = id,
        title = if (isEpisode) seriesTitle!! else title,
        caption = if (isEpisode) episodeCaption(season, episode, title) else year.takeIf { it > 0 }?.toString(),
        imageUrl = image,
        thumbhash = hash,
        landscape = landscape,
        progress = progressOf(position, duration),
        watched = played,
        backdropUrl = backdropUrl,
        backdropThumbhash = backdropThumbhash,
        logoUrl = logoUrl,
        overview = overview,
        metaLine = listOfNotNull(year.takeIf { it > 0 }?.toString(), formatRuntime(runtime), rating).joinToString(" · "),
        openTarget = id,
        kind = type,
        inWatchlist = inWatchlist,
        badges = badges,
        favorite = favorite,
        unwatchedEpisode = type == "episode" && org.siloserver.silo.model.settings.EpisodeSpoilers.isUnwatched(played, null, position),
        imageIsStill = imageIsStill,
    )
}

fun SectionItem.toCard(landscape: Boolean): CardModel = card(
    contentId, type, title, seriesTitle, seasonNumber, episodeNumber, year, runtime, contentRating, overview,
    positionSeconds, durationSeconds, posterUrl, posterThumbhash, backdropUrl, backdropThumbhash, logoUrl,
    userState?.played == true, landscape, inWatchlist = userState?.inWatchlist == true,
    badges = org.siloserver.silo.overlays.OverlayDataExtractor.fromSectionItem(this), favorite = userState?.isFavorite == true,
    imageIsStill = org.siloserver.silo.model.settings.EpisodeSpoilers.selectedImageIsStill(this).takeIf { landscape } ?: posterIsEpisodeStill,
).copy(progressUpdatedAt = progressUpdatedAt, seriesId = seriesId)

fun BrowseItem.toCard(): CardModel = card(
    contentId, type, title, seriesTitle, seasonNumber, episodeNumber, year, runtime, contentRating, overview,
    positionSeconds, durationSeconds, posterUrl, posterThumbhash, backdropUrl, backdropThumbhash, logoUrl = null,
    played = userState?.played == true, landscape = false, inWatchlist = userState?.inWatchlist == true,
    badges = org.siloserver.silo.overlays.OverlayDataExtractor.fromBrowseItem(this), favorite = userState?.isFavorite == true,
    imageIsStill = posterIsEpisodeStill,
)
