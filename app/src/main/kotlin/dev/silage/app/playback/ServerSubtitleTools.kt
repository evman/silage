package dev.silage.app.playback

import dev.silage.app.playback.hud.AiOption
import dev.silage.app.playback.hud.SubtitleHit
import dev.silage.app.playback.hud.SubtitleTools
import org.siloserver.silo.model.subtitles.SubtitleAiJobKind
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleResult
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SubtitlesRepository
import java.util.Locale

/** A two-letter language code ("en") for "en", "eng", "en-US" or "English"; null when unknown. */
fun languageCode(value: String?): String? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val base = v.substringBefore('-').substringBefore('_').lowercase()
    if (base.length == 2) return base
    return Locale.getAvailableLocales().firstOrNull { l ->
        runCatching { l.isO3Language.equals(base, true) }.getOrDefault(false) || l.getDisplayLanguage(Locale.ENGLISH).equals(v, true)
    }?.language?.takeIf { it.isNotEmpty() }
}

/** "English" for "en". */
fun languageName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

/** The languages to search: the profile's subtitle (or interface) language, then English. */
fun searchLanguages(profileSubtitleLanguage: String?, profileLanguage: String?): List<String> =
    listOfNotNull(languageCode(profileSubtitleLanguage) ?: languageCode(profileLanguage), "en").distinct()

/** A provider hit as a row: "English · SDH · 92% match" over the release name. */
fun SubtitleResult.toHit(): SubtitleHit = SubtitleHit(
    label = releaseName.ifBlank { "Untitled" },
    detail = listOfNotNull(
        languageName(languageCode(language) ?: language),
        "SDH".takeIf { hearingImpaired },
        format.uppercase().takeIf { it.isNotBlank() },
        score.takeIf { it > 0 }?.let { "${it.toInt()}% match" },
        provider.replaceFirstChar { it.uppercase() },
    ).joinToString(" · "),
    key = this,
)

/** The AI choices: transcribe the playing audio, and also translate it when the profile reads another language. */
fun aiChoices(audioLanguage: String?, profileLanguage: String?): List<AiOption> {
    val audio = languageCode(audioLanguage)
    val wanted = languageCode(profileLanguage)
    val heard = audio?.let { " (${languageName(it)})" } ?: ""
    return buildList {
        add(AiOption("Transcribe the audio$heard", AiRequest(SubtitleAiJobKind.Transcribe, audio)))
        if (wanted != null && wanted != audio) {
            add(AiOption("Transcribe and translate into ${languageName(wanted)}", AiRequest(SubtitleAiJobKind.TranscribeTranslate, wanted)))
        }
    }
}

data class AiRequest(val kind: String, val targetLanguage: String?)

/**
 * [SubtitleTools] over Silo's subtitle API for one playing file.
 * [audioOrdinal] is the playing audio track (transcription's source).
 */
class ServerSubtitleTools(
    private val repository: SubtitlesRepository,
    private val mediaFileId: Int,
    private val languages: List<String>,
    private val audioOrdinal: Int,
    private val audioLanguage: String?,
    private val profileLanguage: String?,
    private val onUse: (Int) -> Unit,
) : SubtitleTools {
    override val searchLabel: String = languages.joinToString(" or ") { languageName(it) }

    override suspend fun search(): Result<List<SubtitleHit>> =
        when (val r = repository.search(SubtitleSearchRequest(mediaFileId, languages))) {
            is ApiResult.Success -> Result.success(r.data.results.sortedByDescending { it.score }.take(40).map { it.toHit() })
            is ApiResult.Error -> Result.failure(Exception(r.message))
            is ApiResult.NetworkError -> Result.failure(Exception("can't reach the server"))
        }

    override suspend fun download(hit: SubtitleHit): Result<Int> {
        val s = hit.key as? SubtitleResult ?: return Result.failure(Exception("not a search result"))
        val request = SubtitleDownloadRequest(mediaFileId, s.provider, s.id, s.language, s.releaseName, s.format, s.score, s.hearingImpaired)
        return when (val r = repository.download(request)) {
            is ApiResult.Success -> Result.success(r.data.subtitle.id)
            is ApiResult.Error -> Result.failure(Exception(r.message))
            is ApiResult.NetworkError -> Result.failure(Exception("can't reach the server"))
        }
    }

    override suspend fun aiOptions(): List<AiOption> {
        val status = (repository.aiStatus() as? ApiResult.Success)?.data ?: return emptyList()
        if (!status.enabled || !status.transcribeEnabled) return emptyList()
        val quota = (repository.aiQuota() as? ApiResult.Success)?.data
        if (quota != null && quota.limited && quota.remaining <= 0) return emptyList()
        return aiChoices(audioLanguage, profileLanguage)
    }

    override suspend fun create(option: AiOption, positionSeconds: Double, onProgress: (Double, String) -> Unit): Result<Int> {
        val ai = option.key as? AiRequest ?: return Result.failure(Exception("not an AI option"))
        val scope = repository.captureJobAuthority()
        val request = SubtitleTranslateRequest(
            mediaFileId = mediaFileId, kind = ai.kind, sourceIndex = audioOrdinal,
            sourceLanguage = languageCode(audioLanguage), targetLanguage = ai.targetLanguage, startPosition = positionSeconds,
        )
        val job = when (val r = repository.translate(request, scope)) {
            is ApiResult.Success -> r.data.job
            is ApiResult.Error -> return Result.failure(Exception(r.message))
            is ApiResult.NetworkError -> return Result.failure(Exception("can't reach the server"))
        }
        return when (val outcome = repository.pollJob(job.id, intervalMs = 2_000, expectedScope = scope) { onProgress(it.progress, it.progressMessage) }) {
            is SubtitlesRepository.SubtitleJobOutcome.Completed ->
                outcome.resultSubtitleId?.let { Result.success(it) } ?: Result.failure(Exception("the server didn't return the subtitles"))
            is SubtitlesRepository.SubtitleJobOutcome.Failed -> Result.failure(Exception(outcome.message ?: "the job failed"))
            SubtitlesRepository.SubtitleJobOutcome.Cancelled -> Result.failure(Exception("the job was cancelled"))
        }
    }

    override fun use(id: Int) = onUse(id)
}
