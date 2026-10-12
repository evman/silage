package org.siloserver.silo.model.ebook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaPerson(
    @SerialName("person_id") val personId: String? = null,
    val name: String = "",
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("photo_thumbhash") val photoThumbhash: String? = null,
)

@Serializable
data class MediaRelatedItem(
    @SerialName("content_id") val contentId: String = "",
    val title: String = "",
    val year: Int? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("series_index") val seriesIndex: Double? = null,
)

@Serializable
data class MediaSeriesGroup(
    val name: String = "",
    val entries: List<MediaRelatedItem> = emptyList(),
)

@Serializable
data class MediaRelatedContent(
    @SerialName("also_by_author") val alsoByAuthor: List<MediaRelatedItem> = emptyList(),
    val similar: List<MediaRelatedItem> = emptyList(),
)

@Serializable
data class EbookMetadata(
    val authors: List<MediaPerson> = emptyList(),
    val publisher: String? = null,
    val series: MediaSeriesGroup? = null,
    val related: MediaRelatedContent = MediaRelatedContent(),
) {
    val authorNames: String?
        get() = authors.mapNotNull { it.name.takeIf(String::isNotBlank) }
            .joinToString(", ")
            .takeIf(String::isNotBlank)
}
