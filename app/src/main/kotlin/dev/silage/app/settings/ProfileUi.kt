package dev.silage.app.settings

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlaySchema
import org.siloserver.silo.repository.SettingsRepository

/** Which shortcut buttons a poster shows when hovered or focused. */
enum class QuickActions(val wire: String, val label: String) {
    Both("both", "Favorite and watched"),
    Favorites("favorites", "Favorite only"),
    Watched("watched", "Watched only"),
    ;

    val showsFavorite get() = this == Both || this == Favorites
    val showsWatched get() = this == Both || this == Watched

    companion object {
        fun of(wire: String?): QuickActions = entries.firstOrNull { it.wire == wire } ?: Both
    }
}

/**
 * The profile-level interface settings Silo's web client edits under Title Pages and
 * Cards & Posters. They live on the server for the profile, so a change here shows in
 * the web client and the other way round.
 */
data class ProfileUi(
    val spoilers: EpisodeSpoilerPrefs = EpisodeSpoilerPrefs.NONE,
    val showAdvisoryAge: Boolean = false,
    val badgesEnabled: Boolean = false,
    val badges: CardOverlayPrefs = OverlaySchema.buildDefaults(),
    val quickActionsEnabled: Boolean = false,
    val quickActions: QuickActions = QuickActions.Both,
    val presentation: CardPresentation = CardPresentation.DEFAULT,
) {
    companion object {
        val KEYS = listOf(
            SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES,
            SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS,
            SettingKeys.CATALOG_SHOW_ADVISORY_AGE,
            SettingKeys.UI_CARD_OVERLAYS_ENABLED,
            SettingKeys.UI_CARD_OVERLAYS,
            SettingKeys.UI_CARD_QUICK_ACTIONS_ENABLED,
            SettingKeys.UI_CARD_QUICK_ACTIONS,
            SettingKeys.UI_CARD_PRESENTATION,
        )

        private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

        /** The settings from the server's effective values; a key the server doesn't know keeps its default. */
        fun from(values: Map<String, JsonElement>): ProfileUi {
            val base = ProfileUi()
            val badges = when (val raw = values[SettingKeys.UI_CARD_OVERLAYS]) {
                is JsonObject -> OverlaySchema.parse(raw.toString())
                is JsonPrimitive -> OverlaySchema.parse(raw.contentOrNull)
                else -> base.badges
            }
            return ProfileUi(
                spoilers = EpisodeSpoilerPrefs(
                    hideImages = values[SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES].flag() ?: false,
                    hideOverviews = values[SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS].flag() ?: false,
                ),
                showAdvisoryAge = values[SettingKeys.CATALOG_SHOW_ADVISORY_AGE].flag() ?: false,
                badgesEnabled = values[SettingKeys.UI_CARD_OVERLAYS_ENABLED].flag() ?: false,
                badges = badges,
                quickActionsEnabled = values[SettingKeys.UI_CARD_QUICK_ACTIONS_ENABLED].flag() ?: false,
                quickActions = QuickActions.of((values[SettingKeys.UI_CARD_QUICK_ACTIONS] as? JsonPrimitive)?.contentOrNull),
                presentation = CardPresentation.decodeOrDefault(values[SettingKeys.UI_CARD_PRESENTATION]),
            )
        }

        fun badgesElement(prefs: CardOverlayPrefs): JsonElement = Json.parseToJsonElement(OverlaySchema.serialize(prefs))
    }
}

/** The page being drawn reads the profile's interface settings from here. */
val LocalProfileUi = staticCompositionLocalOf { ProfileUi() }

/**
 * Loads the profile's interface settings and saves changes. A change shows at once and is
 * put back (with a message) when the server refuses it.
 */
class ProfileUiStore(private val repository: SettingsRepository) {
    private val _state = MutableStateFlow(ProfileUi())
    val state: StateFlow<ProfileUi> = _state

    /** Why the last save failed, until the next one succeeds. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    suspend fun load() {
        val result = runCatching { repository.getEffectiveValues(keys = ProfileUi.KEYS) }.getOrNull()
        if (result is ApiResult.Success) _state.value = ProfileUi.from(result.data.mapValues { it.value.value })
    }

    /** Applies [change] now and writes [value] to [key]; reverts [change] if the server says no. */
    suspend fun save(key: String, value: JsonElement, change: (ProfileUi) -> ProfileUi) {
        val before = _state.value
        _state.value = change(before)
        val result = runCatching { repository.setProfileValue(key, value) }.getOrNull()
        if (result is ApiResult.Success) {
            _problem.value = null
        } else {
            _state.value = before
            _problem.value = when (result) {
                is ApiResult.Error -> "Couldn't save that: ${result.message}"
                else -> "Couldn't reach the server to save that."
            }
        }
    }

    suspend fun setFlag(key: String, on: Boolean, change: (ProfileUi, Boolean) -> ProfileUi) =
        save(key, JsonPrimitive(on)) { change(it, on) }
}
