package dev.silage.app.playback

import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO

/**
 * What to ask the server for (Settings → Video): a [preference] it plans with ("auto" lets it play
 * the original whenever it can, or a resolution such as "1080p" it converts down to), and a hard
 * [maxBitrateKbps] it must stay under (null: no limit). A file over the limit is converted.
 */
data class QualityPrefs(val preference: String = PLAYBACK_QUALITY_AUTO, val maxBitrateKbps: Int? = null) {
    companion object {
        /** The resolutions offered in Settings, with "auto" first; each is a `quality_preference` the server takes. */
        val Preferences = listOf(PLAYBACK_QUALITY_AUTO to "Auto (original when possible)", "2160p" to "4K", "1080p" to "1080p", "720p" to "720p", "480p" to "480p")

        /** Bitrate limits offered in Settings, in kbps; null is no limit. */
        val Limits: List<Pair<Int?, String>> = listOf(null to "No limit", 80_000 to "80 Mbps", 60_000 to "60 Mbps", 40_000 to "40 Mbps",
            20_000 to "20 Mbps", 10_000 to "10 Mbps", 8_000 to "8 Mbps", 4_000 to "4 Mbps")
    }
}
