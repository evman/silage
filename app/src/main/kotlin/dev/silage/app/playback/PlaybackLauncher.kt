package dev.silage.app.playback

import dev.silage.app.detail.toOption
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
    /** Set for an episode, so the player can find what comes next. */
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val recap: TimeRange? = null,
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
            seriesId = item.seriesId,
            seriesTitle = item.seriesTitle,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            recap = item.recap,
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
        val sourceAudio: List<org.siloserver.silo.model.catalog.AudioTrack> = emptyList(),
        val direct: Boolean = true,
        val selectedAudioOrdinal: Int? = null,
        val fileId: Int? = null,
        /** Source clock = player clock + offset, including subsequent audio switches. */
        val timelineOffsetSeconds: Double = 0.0,
        /** Which copy is playing and how, for Playback Info ("4K · HDR · original file"). */
        val versionLabel: String? = null,
        /**
         * A converted (HLS) stream from the Silo server itself: its playlist and
         * segments need the signed-in headers, which expire, so the player keeps
         * them fresh while it plays.
         */
        val needsMediaAuth: Boolean = false,
        /**
         * The title's runtime from the plan. A converted (HLS) stream's own
         * duration is only what the server has produced so far, so the seek
         * bar would shrink and jump as it grows.
         */
        val sourceDurationSeconds: Double? = null,
        /** Settings say no subtitles: also switch off any the file itself marks as default. */
        val subtitlesOff: Boolean = false,
    ) : LaunchResult {
        fun sourcePositionSeconds(playerSeconds: Double): Double = playerSeconds + timelineOffsetSeconds
    }
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
    /** Without a copy chosen: the one last played (Settings: Last played) instead of the best one. */
    private val preferLastPlayed: () -> Boolean = { false },
    /** The signed-in request headers (Authorization, profile) for Silo's own media URLs. */
    private val mediaAuth: suspend () -> Map<String, String> = { emptyMap() },
    /** Default audio and subtitle languages on this PC; empty leaves both to the server. */
    private val languages: () -> LanguagePrefs = { LanguagePrefs() },
) {
    /**
     * [fileId]: the copy the user chose on the detail page. Without one (Home,
     * Next Episode, Shuffle) or when it's gone: the best copy (4K before 1080p,
     * Dolby Vision before HDR before SDR), or the last played under that setting.
     */
    suspend fun launch(contentId: String, fromStart: Boolean = false, fileId: Int? = null, audioTrackIndex: Int? = null, positionSeconds: Double? = null,
        /** A subtitle stored on the server (downloaded or AI-made) to switch on instead of the plan's choice. */
        storedSubtitleId: Int? = null): LaunchResult {
        val item = when (val r = detail(contentId)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return LaunchResult.Failed("Couldn't load this item: ${r.message}")
            is ApiResult.NetworkError -> return LaunchResult.Failed("Can't reach the server. Try again.")
        }
        val chosen = item.versions.firstOrNull { it.fileId == fileId } ?: defaultCopy(item.versions, item.userData?.lastFileId, preferLastPlayed())
        val playFileId = chosen?.fileId ?: return LaunchResult.Failed("This item has no playable file.")
        val profile = profileId() ?: return LaunchResult.Failed("No profile selected.")
        val prefs = languages()
        // An audio switch in the player names its track; otherwise the preferred language's.
        val preferredAudio = if (audioTrackIndex == null) preferredAudioOrdinal(chosen.audioTracks.orEmpty(), prefs.audio) else null
        val requestedAudio = audioTrackIndex ?: preferredAudio

        val request = PlaybackStartRequestV3(
            clientFeatures = playbackClientFeaturesV3(context),
            fileId = playFileId,
            profileId = profile,
            playbackAttemptId = newAttemptId(),
            audioTrackIndex = requestedAudio,
            startPosition = positionSeconds,
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
        // The original file's link is signed; a converted stream's playlist and
        // segments are fetched like any other Silo request, with the sign-in
        // headers (as the Android app's media interceptor adds them).
        val needsMediaAuth = !direct && sameOrigin(url, base)
        val headers = if (needsMediaAuth) mediaAuth() + plan.stream.effectiveRequestHeaders else plan.stream.effectiveRequestHeaders
        val stored = storedSubtitleId?.let { id ->
            val key = org.siloserver.silo.model.subtitles.SubtitleSyncState.storedKey(id)
            plan.subtitle.inventory.firstOrNull { it.syncKey == key && it.delivery == SUBTITLE_DELIVERY_SIDECAR && it.url != null }
        }
        val selectedAudio = (if (direct && preferredAudio != null) preferredAudio else null)
            ?: plan.selectedTracks.audio?.index ?: requestedAudio ?: chosen.effectiveAudioTrackIndex
        // Tracks mpv can show: sidecar files, and in direct play the file's own (embedded) ones.
        val usable = plan.subtitle.inventory.filter {
            (it.delivery == SUBTITLE_DELIVERY_SIDECAR && it.url != null) || (direct && it.source == "embedded")
        }
        val pick = if (stored != null) SubtitlePick.Server
            else pickSubtitle(usable, prefs, chosen.audioTracks?.getOrNull(selectedAudio ?: -1)?.language)
        val selectedId = when (pick) {
            SubtitlePick.Server -> stored?.trackId ?: plan.selectedTracks.subtitle?.id?.takeIf { plan.subtitle.mode != PlaybackSubtitleModeV3.OFF }
            SubtitlePick.Off -> null
            is SubtitlePick.Track -> pick.trackId
        }
        val subtitles = plan.subtitle.inventory
            .filter { it.delivery == SUBTITLE_DELIVERY_SIDECAR && it.url != null && !(direct && it.source == "embedded") }
            .map { s ->
                val link = s.url!!.let { if (it.startsWith("/")) base + it else it }
                SubtitleLoad(link, subtitleTitle(s), s.language, select = s.trackId == selectedId)
            }
        val embeddedStream = if (direct) {
            plan.subtitle.inventory.firstOrNull { it.trackId == selectedId && it.source == "embedded" }?.let { sel ->
                val fromUrl = sel.url?.substringAfter("embedded_stream_index=", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                // The plan's embedded stream is its own choice; ours is read from the track's link.
                if (pick is SubtitlePick.Track) fromUrl ?: plan.subtitle.embedded?.streamIndex
                else plan.subtitle.embedded?.streamIndex ?: fromUrl
            }
        } else {
            null
        }
        return LaunchResult.Ready(
            sessionId, url, headers,
            // A packaged replan can begin at source 42s while mpv's clock starts
            // at zero. Seeking to the source position again would skip twice.
            startSeconds = if (plan.timeline.timelineOffsetSeconds != 0.0) plan.timeline.playerStartSeconds
                else positionSeconds ?: playStart(item.userData, fromStart),
            meta = PlaybackMeta.of(item),
            subtitles = subtitles, embeddedSubtitleStream = embeddedStream,
            sourceAudio = chosen.audioTracks.orEmpty(), direct = direct,
            selectedAudioOrdinal = selectedAudio,
            subtitlesOff = pick == SubtitlePick.Off,
            fileId = playFileId, timelineOffsetSeconds = plan.timeline.timelineOffsetSeconds,
            needsMediaAuth = needsMediaAuth,
            sourceDurationSeconds = plan.source.durationSeconds?.takeIf { it > 0 },
            versionLabel = listOf(chosen.toOption().short, if (direct) "original file" else "converted by the server").joinToString(" · "),
        )
    }
}

/** The copy to play when none was chosen: see [PlaybackLauncher.launch]. */
internal fun defaultCopy(versions: List<org.siloserver.silo.model.catalog.FileVersion>, lastFileId: Int?, preferLastPlayed: Boolean) =
    versions.firstOrNull { preferLastPlayed && it.fileId == lastFileId }
        ?: versions.maxByOrNull { it.toOption().rank }

/** Whether [url] is on the same scheme, host and port as [server]. */
internal fun sameOrigin(url: String, server: String): Boolean = runCatching {
    val a = java.net.URI(url); val b = java.net.URI(server)
    fun port(u: java.net.URI) = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80
    a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
}.getOrDefault(false)
