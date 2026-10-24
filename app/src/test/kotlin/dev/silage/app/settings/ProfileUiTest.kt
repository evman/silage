package dev.silage.app.settings

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.settings.CardCaption
import org.siloserver.silo.model.settings.CardPosterSize
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.overlays.OverlayId
import org.siloserver.silo.overlays.OverlayPosition
import org.siloserver.silo.overlays.PresetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileUiTest {
    @Test fun aServerThatKnowsNoneOfTheKeysLeavesEverythingOff() {
        val ui = ProfileUi.from(emptyMap())
        assertFalse(ui.spoilers.hideImages)
        assertFalse(ui.spoilers.hideOverviews)
        assertFalse(ui.showAdvisoryAge)
        assertFalse(ui.badgesEnabled)
        assertFalse(ui.quickActionsEnabled)
        assertEquals(CardPosterSize.Standard, ui.presentation.posterSize)
    }

    @Test fun readsEachSettingFromItsKey() {
        val ui = ProfileUi.from(mapOf(
            SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES to JsonPrimitive(true),
            SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS to JsonPrimitive(true),
            SettingKeys.CATALOG_SHOW_ADVISORY_AGE to JsonPrimitive(true),
            SettingKeys.UI_CARD_OVERLAYS_ENABLED to JsonPrimitive(true),
            SettingKeys.UI_CARD_QUICK_ACTIONS_ENABLED to JsonPrimitive(true),
            SettingKeys.UI_CARD_QUICK_ACTIONS to JsonPrimitive("favorites"),
            SettingKeys.UI_CARD_PRESENTATION to JsonObject(mapOf("poster_size" to JsonPrimitive("large"), "caption" to JsonPrimitive("artwork"))),
        ))
        assertTrue(ui.spoilers.hideImages && ui.spoilers.hideOverviews)
        assertTrue(ui.showAdvisoryAge && ui.badgesEnabled && ui.quickActionsEnabled)
        assertEquals(QuickActions.Favorites, ui.quickActions)
        assertTrue(ui.quickActions.showsFavorite)
        assertFalse(ui.quickActions.showsWatched)
        assertEquals(CardPosterSize.Large, ui.presentation.posterSize)
        assertEquals(CardCaption.Artwork, ui.presentation.caption)
    }

    @Test fun anUnknownQuickActionModeMeansBoth() {
        assertEquals(QuickActions.Both, QuickActions.of("nonesuch"))
        assertEquals(QuickActions.Both, QuickActions.of(null))
    }

    @Test fun badgeChoicesSurviveAWriteAndARead() {
        val changed = ProfileUi().badges.copy(preset = PresetId.Vibrant)
            .withBadge(OverlayId.Year) { it.copy(enabled = true, position = OverlayPosition.TopLeft, accentColor = "#f5c518", showIcon = true) }
        val back = ProfileUi.from(mapOf(SettingKeys.UI_CARD_OVERLAYS to ProfileUi.badgesElement(changed))).badges
        assertEquals(PresetId.Vibrant, back.preset)
        val year = back.items.getValue(OverlayId.Year)
        assertTrue(year.enabled)
        assertEquals(OverlayPosition.TopLeft, year.position)
        assertEquals("#f5c518", year.accentColor)
        assertEquals(true, year.showIcon)
    }

    @Test fun cyclingWrapsRound() {
        assertEquals(OverlayPosition.TopRight, cycle(OverlayPosition.entries, OverlayPosition.TopLeft))
        assertEquals(OverlayPosition.TopLeft, cycle(OverlayPosition.entries, OverlayPosition.BottomRight))
    }
}
