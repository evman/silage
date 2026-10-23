package dev.silage.app.playback

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlaybackSubtitleInventoryItemV3
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguagePrefsTest {
    private val audio = listOf(
        AudioTrack(index = 1, language = "eng", title = "Commentary"),
        AudioTrack(index = 2, language = "jpn", isDefault = true),
        AudioTrack(index = 3, language = "eng"),
    )

    @Test fun preferredAudioSkipsCommentary() {
        assertEquals(2, preferredAudioOrdinal(audio, "en"))
        assertEquals(1, preferredAudioOrdinal(audio, "ja"))
        assertEquals(null, preferredAudioOrdinal(audio, "fr"))
        assertEquals(null, preferredAudioOrdinal(audio, null))
    }

    private fun sub(id: String, lang: String?, forced: Boolean = false, sdh: Boolean = false) =
        PlaybackSubtitleInventoryItemV3(trackId = id, combinedIndex = 0, source = "embedded", language = lang, forced = forced, hearingImpaired = sdh)

    private val subs = listOf(sub("sdh", "en", sdh = true), sub("en", "eng"), sub("forced-en", "en", forced = true), sub("fr", "fr"))

    @Test fun noPreferenceKeepsTheServersChoice() {
        assertEquals(SubtitlePick.Server, pickSubtitle(subs, LanguagePrefs(), "en"))
    }

    @Test fun onPicksTheFullTrackInTheLanguageBeforeSdh() {
        assertEquals(SubtitlePick.Track("en"), pickSubtitle(subs, LanguagePrefs(subtitleMode = SubtitleModes.On, subtitle = "en"), "ja"))
        assertEquals(SubtitlePick.Track("fr"), pickSubtitle(subs, LanguagePrefs(audio = "fr", subtitleMode = SubtitleModes.On), "fr"))
        // Nothing in that language: the server's choice stands.
        assertEquals(SubtitlePick.Server, pickSubtitle(subs, LanguagePrefs(subtitleMode = SubtitleModes.On, subtitle = "de"), "en"))
    }

    @Test fun forcedOnlyAndOff() {
        assertEquals(SubtitlePick.Track("forced-en"), pickSubtitle(subs, LanguagePrefs(subtitleMode = SubtitleModes.Forced), "en"))
        assertEquals(SubtitlePick.Off, pickSubtitle(subs.filterNot { it.forced }, LanguagePrefs(subtitleMode = SubtitleModes.Forced), "en"))
        assertEquals(SubtitlePick.Off, pickSubtitle(subs, LanguagePrefs(subtitleMode = SubtitleModes.Off), "en"))
    }

    @Test fun audioDevicesKeepWasapiOutputsOnWindows() {
        val json = """[{"name":"auto","description":"Autoselect device"},
            {"name":"wasapi/{a}","description":"Speakers (Realtek)"},
            {"name":"wasapi/{b}","description":"LG TV (NVIDIA High Definition Audio)"},
            {"name":"openal","description":"Default (openal)"}]"""
        assertEquals(listOf("Speakers (Realtek)", "LG TV (NVIDIA High Definition Audio)"),
            AudioDevices.parse(json, "Windows 11").map { it.description })
        assertEquals(3, AudioDevices.parse(json, "Linux").size)
        assertEquals(emptyList(), AudioDevices.parse("oops", "Windows 11"))
    }
}
