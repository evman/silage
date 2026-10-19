package dev.silage.app.playback

import dev.silage.app.playback.hud.FinderUi
import dev.silage.app.playback.hud.SubtitleExtra
import dev.silage.app.playback.hud.aiProgressLabel
import dev.silage.app.playback.hud.extraLabel
import dev.silage.app.playback.hud.subtitleExtras
import org.siloserver.silo.model.subtitles.SubtitleAiJobKind
import org.siloserver.silo.model.subtitles.SubtitleResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerSubtitleToolsTest {
    @Test fun languageCodesFromWhateverTheProfileStores() {
        assertEquals("en", languageCode("en"))
        assertEquals("en", languageCode("en-US"))
        assertEquals("en", languageCode("eng"))
        assertEquals("fr", languageCode("French"))
        assertEquals(null, languageCode(""))
        assertEquals(null, languageCode(null))
    }

    @Test fun searchesTheProfilesLanguageThenEnglish() {
        assertEquals(listOf("fr", "en"), searchLanguages("fr", "de"))
        assertEquals(listOf("de", "en"), searchLanguages(null, "de"))
        assertEquals(listOf("en"), searchLanguages("eng", null))
        assertEquals(listOf("en"), searchLanguages(null, null))
    }

    @Test fun aiOffersTranslationOnlyWhenTheProfileReadsAnotherLanguage() {
        val same = aiChoices("en", "en")
        assertEquals(listOf("Transcribe the audio (English)"), same.map { it.label })
        val other = aiChoices("jpn", "en")
        assertEquals(listOf("Transcribe the audio (Japanese)", "Transcribe and translate into English"), other.map { it.label })
        assertEquals(AiRequest(SubtitleAiJobKind.TranscribeTranslate, "en"), other[1].key)
        assertEquals(AiRequest(SubtitleAiJobKind.Transcribe, "ja"), other[0].key)
    }

    @Test fun hitsReadLikeChoices() {
        val hit = SubtitleResult(id = "9", provider = "opensubtitles", language = "en", releaseName = "Movie.2160p.WEB", format = "srt", score = 92.4, hearingImpaired = true).toHit()
        assertEquals("Movie.2160p.WEB", hit.label)
        assertEquals("English · SDH · SRT · 92% match · Opensubtitles", hit.detail)
    }

    @Test fun subtitlesMenuExtras() {
        assertEquals(emptyList(), subtitleExtras(false, FinderUi()))
        assertEquals(listOf(SubtitleExtra.Find), subtitleExtras(true, FinderUi()))
        val withAi = FinderUi(aiOptions = aiChoices("en", "en"))
        assertEquals(listOf(SubtitleExtra.Find, SubtitleExtra.Ai), subtitleExtras(true, withAi))
        assertEquals("Creating subtitles · 42%", extraLabel(SubtitleExtra.Ai, FinderUi(aiProgress = aiProgressLabel(0.42, ""))))
        assertEquals("Creating subtitles · 100% · aligning", aiProgressLabel(1.7, "aligning"))
    }
}
