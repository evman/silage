package dev.silage.app.playback

import dev.silage.app.ui.ShowPrefs
import dev.silage.app.ui.UiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.siloserver.silo.repository.ProfileRepository

/** Where a show's choices are kept: separately for each profile. */
internal fun showKey(profile: String?, series: String): String = "${profile.orEmpty()}|$series"

/** The defaults with a show's own choices on top. */
internal fun LanguagePrefs.forShow(show: ShowPrefs): LanguagePrefs = LanguagePrefs(
    audio = show.audio ?: audio,
    subtitleMode = show.subtitleMode ?: subtitleMode,
    subtitle = show.subtitle ?: subtitle,
)

/** Auto-skip with a show's own choice on top: it covers intros and recaps; credits stay with Settings. */
fun autoSkipFor(defaults: dev.silage.app.playback.hud.AutoSkip, show: Boolean?): dev.silage.app.playback.hud.AutoSkip =
    if (show == null) defaults else defaults.copy(intro = show, recap = show)

/** What a profile chose while watching one show. */
class ShowMemory(private val prefs: UiPrefs, private val key: String) {
    val current: ShowPrefs get() = prefs.showPrefs(key)

    fun audio(language: String?) { languageCode(language)?.let { code -> prefs.updateShowPrefs(key) { it.copy(audio = code) } } }

    fun subtitle(language: String?, off: Boolean) {
        val code = languageCode(language)
        if (off) prefs.updateShowPrefs(key) { it.copy(subtitleMode = SubtitleModes.Off, subtitle = null) }
        else if (code != null) prefs.updateShowPrefs(key) { it.copy(subtitleMode = SubtitleModes.On, subtitle = code) }
    }

    fun autoSkip(value: Boolean?) = prefs.updateShowPrefs(key) { it.copy(autoSkip = value) }
}

/** This profile's memory of [series]; null when it isn't a show. */
internal fun showMemory(koin: Koin, series: String?): ShowMemory? = series?.let {
    val profile = runCatching { runBlocking(Dispatchers.Default) { koin.get<ProfileRepository>().getActiveProfileId() } }.getOrNull()
    ShowMemory(koin.get(), showKey(profile, it))
}

/** The language defaults for [series], with what this profile chose for it. */
internal fun showLanguages(koin: Koin, series: String?): LanguagePrefs {
    val base = koin.get<UiPrefs>().languages
    return showMemory(koin, series)?.let { base.forShow(it.current) } ?: base
}
