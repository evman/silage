package dev.silage.app.playback

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlaybackSubtitleInventoryItemV3

/**
 * The default audio and subtitles for new videos on this PC (Settings → Playback and
 * Subtitles). Unset values leave the choice to the server, which follows the profile.
 */
data class LanguagePrefs(
    /** Two-letter audio language ("en"), or null for the server's / file's choice. */
    val audio: String? = null,
    /** null (the server's choice), [SubtitleModes.Off], [SubtitleModes.On] or [SubtitleModes.Forced]. */
    val subtitleMode: String? = null,
    /** Two-letter subtitle language; null follows [audio], then the language playing. */
    val subtitle: String? = null,
)

object SubtitleModes {
    const val Off = "off"
    const val On = "on"
    /** Only forced subtitles (signs and foreign-language lines). */
    const val Forced = "forced"
}

/** Languages offered in Settings, by two-letter code, in a familiar order. */
val SettingsLanguages: List<String> = listOf(
    "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da", "fi", "pl", "cs", "hu", "ro", "el", "tr", "ru", "uk",
    "ar", "he", "hi", "th", "vi", "id", "ja", "ko", "zh",
)

/**
 * The source audio track (by position) in [language], or null when there's no
 * preference or no such track. Among several, the file's default wins, then one
 * that isn't commentary.
 */
fun preferredAudioOrdinal(tracks: List<AudioTrack>, language: String?): Int? {
    val want = languageCode(language) ?: return null
    val matches = tracks.withIndex().filter { languageCode(it.value.language) == want }
    if (matches.isEmpty()) return null
    val plain = matches.filterNot { (it.value.title ?: "").contains("commentary", ignoreCase = true) }
    return (plain.firstOrNull { it.value.isDefault } ?: plain.firstOrNull() ?: matches.first()).index
}

/** The subtitle to start with under [LanguagePrefs]. */
sealed interface SubtitlePick {
    /** No preference here: keep the server's choice. */
    data object Server : SubtitlePick
    data object Off : SubtitlePick
    data class Track(val trackId: String) : SubtitlePick
}

/**
 * Picks from the tracks the player can load ([inventory], already filtered to usable
 * ones). [playingAudio] is the language of the audio that will play.
 */
fun pickSubtitle(inventory: List<PlaybackSubtitleInventoryItemV3>, prefs: LanguagePrefs, playingAudio: String?): SubtitlePick {
    val mode = prefs.subtitleMode ?: return SubtitlePick.Server
    if (mode == SubtitleModes.Off) return SubtitlePick.Off
    val want = languageCode(prefs.subtitle) ?: languageCode(prefs.audio) ?: languageCode(playingAudio)
        ?: languageCode(java.util.Locale.getDefault().language)
    fun inLanguage(t: PlaybackSubtitleInventoryItemV3) = want != null && languageCode(t.language) == want
    return when (mode) {
        SubtitleModes.On -> {
            val full = inventory.filter { inLanguage(it) && !it.forced }
            val best = full.firstOrNull { !it.hearingImpaired } ?: full.firstOrNull()
            best?.let { SubtitlePick.Track(it.trackId) } ?: SubtitlePick.Server
        }
        SubtitleModes.Forced -> {
            val forced = inventory.filter { it.forced }
            val audio = languageCode(playingAudio)
            val best = forced.firstOrNull(::inLanguage) ?: forced.firstOrNull { audio != null && languageCode(it.language) == audio }
            best?.let { SubtitlePick.Track(it.trackId) } ?: SubtitlePick.Off
        }
        else -> SubtitlePick.Server
    }
}
