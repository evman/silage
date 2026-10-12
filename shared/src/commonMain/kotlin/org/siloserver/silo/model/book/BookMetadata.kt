package org.siloserver.silo.model.book

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Book-specific metadata attached to an `ItemDetail` when the item's
 * top-level `type` is one of "book" / "ebook" / "comic" / "manga".
 * Reuses the parent ItemDetail's contentId, title, coverUrl, etc.; this
 * record only holds fields specific to text/comic content.
 *
 * Server side is still in flight, so field shapes here are forward-
 * looking — they should be the union of what an EPUB / PDF / comic
 * needs to render and what the catalog row needs to display.
 */
@Serializable
data class BookMetadata(
    val author: String? = null,
    /** Comma-separated author list when there's more than one. The
     *  catalog can split into a list later; keeping it a single string
     *  for v1 keeps the schema additive. */
    val authors: List<String> = emptyList(),
    val format: String = BookFormat.Unknown.wire,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
    /** Server-relative streaming / download URL. The reader dispatches on
     *  [format] to pick a renderer once the bytes are local. */
    @SerialName("file_url") val fileUrl: String? = null,
    val publisher: String? = null,
    @SerialName("published_date") val publishedDate: String? = null,
    val isbn: String? = null,
    /** Optional language tag (e.g. "en", "fr"). Used by EPUB renderer
     *  hyphenation + RTL detection. */
    val language: String? = null,
    /** Reflowable EPUBs report 0 pages until rendered; this is the
     *  catalog's best estimate. Renderer-derived pagination overrides
     *  it at runtime. */
    @SerialName("word_count") val wordCount: Int? = null,
) {
    /** Type-safe accessor for the format wire string. */
    fun formatEnum(): BookFormat = BookFormat.fromWire(format)
}
