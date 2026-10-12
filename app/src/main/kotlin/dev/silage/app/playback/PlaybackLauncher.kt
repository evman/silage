package dev.silage.app.playback

import dev.silage.app.detail.resumePosition
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackSubtitleInventoryItemV3
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_SIDECAR
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.model.playback.playbackClientFeaturesV3
import org.siloserver.silo.network.ApiResult
import java.util.UUID

/** What the player HUD shows about the item. */
data class PlaybackMeta(
    val title: String = "",
    val line: String? = null,
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
) {
    companion object {
        fun of(item: WatchDetail) = PlaybackMeta(
            title = item.title,
            line = if (item.seriesTitle != null) {
                listOfNotNull(
                    item.seriesTitle,
                    listOfNotNull(item.seasonNumber?.let { "S$it" }, item.episodeNumber?.let { "E$it" }).joinToString(" ").ifBlank { null },
                ).joinToString(" · ")
            } else {
                item.year?.toString()
            },
            intro = item.intro,
            credits = item.credits,
        )
    }
}

/**
 * Where playback starts: the saved position of an unfinished item, unless
 * starting over. Same rule as the detail page's "Resume from…" label.
 */
fun playStart(userData: org.siloserver.silo.model.catalog.LeafItemUserData?, fromStart: Boolean): Double? =
    if (fromStart) null else userData.resumePosition()

/** A subtitle mpv loads from the server (`sub-add`), selected when the profile wants it shown. */
data class SubtitleLoad(val url: String, val title: String, val language: String?, val select: Boolean)

private fun subtitleTitle(item: PlaybackSubtitleInventoryItemV3): String {
    val language = item.language?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { null } }
    return listOfNotNull(
        language ?: item.label ?: "Subtitle ${item.combinedIndex + 1}",
        "SDH".takeIf { item.hearingImpaired },
        "Forced".takeIf { item.forced },
    ).joinToString(" ")
}

sealed interface LaunchResult {
    data class Ready(
        val sessionId: String,
        val url: String,
        val headers: Map<String, String>,
        val startSeconds: Double?,
        val meta: PlaybackMeta = PlaybackMeta(),
        /** Subtitles to load from their links (all of them for a transcode; external ones for direct play). */
        val subtitles: List<SubtitleLoad> = emptyList(),
        /** FFmpeg stream index of a built-in subtitle to switch on in direct play. */
        val embeddedSubtitleStream: Int? = null,
    ) : LaunchResult
    data class Failed(val message: String) : LaunchResult
}

/** Detail → first file version → v3 playback start → a URL mpv can open. */
class PlaybackLauncher(
    private val detail: suspend (contentId: String) -> ApiResult<WatchDetail>,
    private val start: suspend (PlaybackStartRequestV3) -> ApiResult<PlaybackDecisionResponseV3>,
    private val profileId: suspend () -> String?,
    private val serverUrl: suspend () -> String,
    private val capabilities: ClientCodecCapabilities = DesktopCapabilities.codecs,
    private val context: ClientPlaybackContext = DesktopCapabilities.context,
    private val newAttemptId: () -> String = { UUID.randomUUID().toString() },
) {
    /** [fileId]: the copy the user chose on the detail page; the first copy when absent or gone. */
    suspend fun launch(contentId: String, fromStart: Boolean = false, fileId: Int? = null): LaunchResult {
        val item = when (val r = detail(contentId)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return LaunchResult.Failed("Couldn't load this item: ${r.message}")
            is ApiResult.NetworkError -> return LaunchResult.Failed("Can't reach the server. Try again.")
        }
        val chosen = item.versions.firstOrNull { it.fileId == fileId } ?: item.versions.firstOrNull()
        val playFileId = chosen?.fileId ?: return LaunchResult.Failed("This item has no playable file.")
        val profile = profileId() ?: return LaunchResult.Failed("No profile selected.")

        val request = PlaybackStartRequestV3(
            clientFeatures = playbackClientFeaturesV3(context),
            fileId = playFileId,
            profileId = profile,
            playbackAttemptId = newAttemptId(),
            subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
            capabilities = capabilities,
            clientPlaybackContext = context,
        )
        val decision = when (val r = start(request)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return LaunchResult.Failed("The server refused playback: ${r.message}")
            is ApiResult.NetworkError -> return LaunchResult.Failed("Can't reach the server. Try again.")
        }
        val plan = decision.playbackPlan ?: return LaunchResult.Failed(
            decision.terminal?.message?.let { "The server couldn't plan playback: $it" }
                ?: "The server couldn't plan playback for this file.",
        )
        val sessionId = decision.sessionId ?: plan.sessionId ?: return LaunchResult.Failed("The server didn't open a playback session.")
        val url = plan.stream.url.let { if (it.startsWith("/")) serverUrl().trimEnd('/') + it else it }
        val base = serverUrl().trimEnd('/')
        val direct = plan.delivery == PlaybackDelivery.ORIGINAL_HTTP
        val selectedId = plan.selectedTracks.subtitle?.id?.takeIf { plan.subtitle.mode != PlaybackSubtitleModeV3.OFF }
        val subtitles = plan.subtitle.inventory
            .filter { it.delivery == SUBTITLE_DELIVERY_SIDECAR && it.url != null && !(direct && it.source == "embedded") }
            .map { s ->
                val link = s.url!!.let { if (it.startsWith("/")) base + it else it }
                SubtitleLoad(link, subtitleTitle(s), s.language, select = s.trackId == selectedId)
            }
        val embeddedStream = if (direct) {
            plan.subtitle.inventory.firstOrNull { it.trackId == selectedId && it.source == "embedded" }?.let { sel ->
                plan.subtitle.embedded?.streamIndex
                    ?: sel.url?.substringAfter("embedded_stream_index=", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
            }
        } else {
            null
        }
        return LaunchResult.Ready(
            sessionId, url, plan.stream.effectiveRequestHeaders, startSeconds = playStart(item.userData, fromStart), meta = PlaybackMeta.of(item),
            subtitles = subtitles, embeddedSubtitleStream = embeddedStream,
        )
    }
}
