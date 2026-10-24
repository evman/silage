@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.silage.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.silage.app.browse.Pill
import dev.silage.app.ui.Silage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.Koin
import org.siloserver.silo.model.settings.CardCaption
import org.siloserver.silo.model.settings.CardPosterSize
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.settings.CardPresentationPreset
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayCategory
import org.siloserver.silo.overlays.OverlayId
import org.siloserver.silo.overlays.OverlayItemConfig
import org.siloserver.silo.overlays.OverlayPosition
import org.siloserver.silo.overlays.OverlayRegistry
import org.siloserver.silo.overlays.PresetId

/** The colours a badge cycles through: the default, then these. */
internal val BadgeColors: List<Pair<String, String?>> = listOf(
    "Default" to null, "Gold" to "#f5c518", "Red" to "#ef4444", "Green" to "#22c55e", "Blue" to "#3b82f6", "White" to "#ffffff",
)

/** The value after [current] in [all], wrapping round. */
internal fun <T> cycle(all: List<T>, current: T): T = all[(all.indexOf(current) + 1) % all.size]

/** [prefs] with badge [id] changed by [change]; unchanged when the profile has no such badge. */
internal fun CardOverlayPrefs.withBadge(id: OverlayId, change: (OverlayItemConfig) -> OverlayItemConfig): CardOverlayPrefs {
    val current = items[id] ?: return this
    return copy(items = items + (id to change(current)))
}

@Composable
private fun OnOff(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Pill("On", selected = on) { onChange(true) }
        Pill("Off", selected = !on) { onChange(false) }
    }
}

@Composable
private fun SaveProblem(store: ProfileUiStore) {
    val problem by store.problem.collectAsState()
    problem?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 17.sp, modifier = Modifier.padding(bottom = 16.dp)) }
}

private typealias Color = androidx.compose.ui.graphics.Color

/** Settings → Title pages: what an episode or title page reveals. Saved on the profile, shared with Silo's web app. */
@Composable
internal fun TitlePagesSection(koin: Koin) {
    val store = remember { koin.get<ProfileUiStore>() }
    val ui by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { store.load() }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SaveProblem(store)
        Heading("Hide spoiler images", "Blur the picture of an episode you haven't started, on cards, the episode list and the episode's own page.")
        OnOff(ui.spoilers.hideImages) { on ->
            scope.launch { store.setFlag(SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES, on) { s, v -> s.copy(spoilers = s.spoilers.copy(hideImages = v)) } }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Hide spoiler descriptions", "Don't show the description of an episode you haven't started.")
        OnOff(ui.spoilers.hideOverviews) { on ->
            scope.launch { store.setFlag(SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS, on) { s, v -> s.copy(spoilers = s.spoilers.copy(hideOverviews = v)) } }
        }
        Spacer(Modifier.height(40.dp))
        Heading("Age guidance", "Show the recommended age (for example 13+) next to the content rating on a title's page, when the server has one.")
        OnOff(ui.showAdvisoryAge) { on ->
            scope.launch { store.setFlag(SettingKeys.CATALOG_SHOW_ADVISORY_AGE, on) { s, v -> s.copy(showAdvisoryAge = v) } }
        }
        Spacer(Modifier.height(48.dp))
    }
}

/** Settings → Posters: badges, shortcut buttons and what sits under each poster. */
@Composable
internal fun PostersSection(koin: Koin) {
    val store = remember { koin.get<ProfileUiStore>() }
    val ui by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { store.load() }
    fun saveBadges(next: CardOverlayPrefs) {
        scope.launch { store.save(SettingKeys.UI_CARD_OVERLAYS, ProfileUi.badgesElement(next)) { it.copy(badges = next) } }
    }
    fun savePresentation(next: CardPresentation) {
        scope.launch { store.save(SettingKeys.UI_CARD_PRESENTATION, next.toJsonElement()) { it.copy(presentation = next) } }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SaveProblem(store)
        Heading("Under each poster", "How big posters are, and what is written under them.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardPresentationPreset.entries.forEach { p -> Pill(p.displayName, selected = ui.presentation.preset == p) { savePresentation(p.presentation) } }
            if (ui.presentation.preset == null) Pill("Custom", selected = true) {}
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CardPosterSize.entries.forEach { z -> Pill(z.displayName, selected = ui.presentation.posterSize == z) { savePresentation(ui.presentation.copy(posterSize = z)) } }
        }
        Spacer(Modifier.height(18.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardCaption.entries.forEach { c -> Pill(c.displayName, selected = ui.presentation.caption == c) { savePresentation(ui.presentation.copy(caption = c)) } }
        }

        Spacer(Modifier.height(40.dp))
        Heading("Shortcut buttons", "Favorite and watched buttons that appear on a poster when you point at it.")
        OnOff(ui.quickActionsEnabled) { on ->
            scope.launch { store.setFlag(SettingKeys.UI_CARD_QUICK_ACTIONS_ENABLED, on) { s, v -> s.copy(quickActionsEnabled = v) } }
        }
        if (ui.quickActionsEnabled) {
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                QuickActions.entries.forEach { q ->
                    Pill(q.label, selected = ui.quickActions == q) {
                        scope.launch { store.save(SettingKeys.UI_CARD_QUICK_ACTIONS, JsonPrimitive(q.wire)) { it.copy(quickActions = q) } }
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
        Heading("Poster badges", "Small labels on a poster's corners: quality, ratings, year and more.")
        OnOff(ui.badgesEnabled) { on ->
            scope.launch { store.setFlag(SettingKeys.UI_CARD_OVERLAYS_ENABLED, on) { s, v -> s.copy(badgesEnabled = v) } }
        }
        if (ui.badgesEnabled) {
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PresetId.entries.forEach { p -> Pill(p.label, selected = ui.badges.preset == p) { saveBadges(ui.badges.copy(preset = p)) } }
            }
            Text(ui.badges.preset.description, color = Silage.Text3, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp))
            OverlayCategory.entries.forEach { category ->
                Spacer(Modifier.height(28.dp))
                Text(category.displayName, color = Silage.Text, fontSize = 22.sp)
                Text(category.description, color = Silage.Text3, fontSize = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
                OverlayRegistry.defs(category).forEach { def ->
                    val cfg = ui.badges.items[def.id] ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(300.dp)) {
                            Text(def.label, color = if (cfg.enabled) Silage.Text else Silage.Text3, fontSize = 19.sp)
                            def.availabilityNote?.let { Text(it, color = Silage.Text3, fontSize = 14.sp) }
                        }
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill(if (cfg.enabled) "On" else "Off", selected = cfg.enabled) { saveBadges(ui.badges.withBadge(def.id) { it.copy(enabled = !it.enabled) }) }
                            if (cfg.enabled) {
                                Pill(cfg.position.displayName) { saveBadges(ui.badges.withBadge(def.id) { it.copy(position = cycle(OverlayPosition.entries, it.position)) }) }
                                val colourName = BadgeColors.firstOrNull { it.second.equals(cfg.accentColor, ignoreCase = true) }?.first ?: "Custom"
                                Pill("Colour: $colourName") {
                                    saveBadges(ui.badges.withBadge(def.id) { c ->
                                        val i = BadgeColors.indexOfFirst { it.second.equals(c.accentColor, ignoreCase = true) }
                                        c.copy(accentColor = BadgeColors[(i + 1) % BadgeColors.size].second)
                                    })
                                }
                                if (def.iconCapable) Pill(if (cfg.showIcon == true) "Icon: on" else "Icon: off", selected = cfg.showIcon == true) {
                                    saveBadges(ui.badges.withBadge(def.id) { it.copy(showIcon = !(it.showIcon ?: false)) })
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
