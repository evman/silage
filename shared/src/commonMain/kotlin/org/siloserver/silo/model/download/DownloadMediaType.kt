package org.siloserver.silo.model.download

/**
 * Media-type classification stamped onto each [DownloadSidecar] at queue
 * time. Determines which section of the Downloads tab a record renders
 * under and which renderer the UI uses.
 *
 * Adding a new type (e.g. comic, podcast) is a single enum entry + a
 * dedicated section renderer; nothing else in the storage / repository
 * layer needs to change.
 *
 * Wire strings are stable — they're persisted in JSON sidecars on disk
 * and must keep their existing spelling across app versions. [Unknown]
 * is the safe fallback for sidecars written before this enum existed.
 */
enum class DownloadMediaType(val wire: String, val displayName: String) {
    Movie("movie", "Movies"),
    TvShow("tv", "TV Shows"),
    Audiobook("audiobook", "Audiobooks"),
    Ebook("ebook", "eBooks"),
    Unknown("", "Other"),
    ;

    companion object {
        /** Tolerant decode: anything not in the enum falls back to [Unknown]
         *  so a sidecar written by a newer client doesn't crash an older one. */
        fun fromWire(value: String?): DownloadMediaType {
            val lower = value?.lowercase() ?: return Unknown
            return entries.firstOrNull { it.wire == lower } ?: Unknown
        }

        /** Maps a server-side catalog `type` string (e.g. ItemDetail.type
         *  which is "movie" / "series") onto our local taxonomy. The
         *  server's `series` becomes our `TvShow`; episodes also map to
         *  TvShow so they cluster under their parent series. */
        fun fromCatalogType(catalogType: String?): DownloadMediaType =
            when (catalogType?.lowercase()) {
                "movie" -> Movie
                "series", "season", "episode" -> TvShow
                "audiobook" -> Audiobook
                "ebook" -> Ebook
                else -> Unknown
            }
    }
}
