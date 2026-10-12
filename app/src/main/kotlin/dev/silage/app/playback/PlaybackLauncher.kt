package dev.silage.app.playback

import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.model.playback.playbackClientFeaturesV3
import org.siloserver.silo.network.ApiResult
import java.util.UUID

sealed interface LaunchResult {
    data class Ready(val sessionId: String, val url: String, val headers: Map<String, String>, val startSeconds: Double?) : LaunchResult
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
    suspend fun launch(contentId: String): LaunchResult {
        val item = when (val r = detail(contentId)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> return LaunchResult.Failed("Couldn't load this item: ${r.message}")
            is ApiResult.NetworkError -> return LaunchResult.Failed("Can't reach the server. Try again.")
        }
        val fileId = item.versions.firstOrNull()?.fileId ?: return LaunchResult.Failed("This item has no playable file.")
        val profile = profileId() ?: return LaunchResult.Failed("No profile selected.")

        val request = PlaybackStartRequestV3(
            clientFeatures = playbackClientFeaturesV3(context),
            fileId = fileId,
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
        return LaunchResult.Ready(sessionId, url, plan.stream.effectiveRequestHeaders, startSeconds = null)
    }
}
