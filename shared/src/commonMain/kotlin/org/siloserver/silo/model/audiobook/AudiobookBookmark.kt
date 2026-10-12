package org.siloserver.silo.model.audiobook

import kotlinx.serialization.Serializable

/**
 * A user-dropped bookmark in an audiobook. Stored locally (per
 * server / profile / contentId) until the server exposes a sync
 * endpoint; the [id] is a client-generated UUID-ish string so it
 * survives the eventual server round-trip.
 */
@Serializable
data class AudiobookBookmark(
    val id: String,
    val positionSeconds: Double,
    /** Title of the chapter the bookmark fell in, captured at drop
     *  time so the list can render it without re-resolving against the
     *  current chapter list. */
    val chapterTitle: String? = null,
    val note: String? = null,
    val createdAtMs: Long,
)
