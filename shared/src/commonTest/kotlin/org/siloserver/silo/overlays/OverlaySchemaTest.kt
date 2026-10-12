package org.siloserver.silo.overlays

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlaySchemaTest {

    @Test
    fun parseNull_returnsBuildDefaults() {
        assertEquals(OverlaySchema.buildDefaults(), OverlaySchema.parse(null))
    }

    @Test
    fun parseEmpty_returnsBuildDefaults() {
        assertEquals(OverlaySchema.buildDefaults(), OverlaySchema.parse(""))
    }

    @Test
    fun parseGarbage_returnsBuildDefaults() {
        assertEquals(OverlaySchema.buildDefaults(), OverlaySchema.parse("not json"))
        assertEquals(OverlaySchema.buildDefaults(), OverlaySchema.parse("[1,2,3]"))
    }

    @Test
    fun defaults_haveEveryRegistryOverlay() {
        val defaults = OverlaySchema.buildDefaults()
        assertEquals(OverlayRegistry.all.size, defaults.items.size)
        for (def in OverlayRegistry.all) {
            val cfg = defaults.items[def.id]
            assertTrue(cfg != null, "missing default for ${def.id}")
            assertEquals(def.defaultEnabled, cfg.enabled)
            assertEquals(def.defaultPosition, cfg.position)
            assertNull(cfg.accentColor)
            assertNull(cfg.showIcon)
        }
        assertEquals(PresetId.Classic, defaults.preset)
        assertEquals(CardOverlayPrefs.CURRENT_VERSION, defaults.version)
        assertTrue(defaults.order.isEmpty())
    }

    @Test
    fun roundTrip_defaults() {
        val prefs = OverlaySchema.buildDefaults()
        assertEquals(prefs, OverlaySchema.parse(OverlaySchema.serialize(prefs)))
    }

    @Test
    fun roundTrip_customized() {
        val base = OverlaySchema.buildDefaults()
        val items = base.items.toMutableMap()
        items[OverlayId.RatingImdb] = OverlayItemConfig(
            enabled = true,
            position = OverlayPosition.TopRight,
            accentColor = "#f5c518",
            showIcon = true,
        )
        items[OverlayId.Resolution] = OverlayItemConfig(
            enabled = false,
            position = OverlayPosition.BottomRight,
            accentColor = null,
            showIcon = false,
        )
        val custom = base.copy(
            preset = PresetId.Vibrant,
            order = listOf(OverlayId.RatingImdb, OverlayId.Resolution, OverlayId.Hdr),
            items = items,
        )
        assertEquals(custom, OverlaySchema.parse(OverlaySchema.serialize(custom)))
    }

    @Test
    fun serialize_omitsNullOptionals_andSortsKeys() {
        val prefs = OverlaySchema.buildDefaults()
        val json = OverlaySchema.serialize(prefs)
        // Top-level keys are sorted: items, order, preset, version.
        val itemsIdx = json.indexOf("\"items\"")
        val orderIdx = json.indexOf("\"order\"")
        val presetIdx = json.indexOf("\"preset\"")
        val versionIdx = json.indexOf("\"version\"")
        assertTrue(itemsIdx in 0 until orderIdx)
        assertTrue(orderIdx < presetIdx)
        assertTrue(presetIdx < versionIdx)
        // No null accent / showIcon leaked into the output.
        assertFalse(json.contains("accentColor"))
        assertFalse(json.contains("showIcon"))
    }

    @Test
    fun parseV2_dropsInvalidAccentAndUnknownIds() {
        val raw = """
            {
              "version": 2,
              "preset": "pill",
              "order": ["resolution", "made_up_overlay"],
              "items": {
                "resolution": {"enabled": true, "position": "top-left", "accentColor": "nothex"},
                "rating_imdb": {"enabled": true, "position": "top-right", "accentColor": "#abcdef", "showIcon": true},
                "made_up_overlay": {"enabled": true, "position": "top-left"}
              }
            }
        """.trimIndent()
        val prefs = OverlaySchema.parse(raw)
        assertEquals(PresetId.Pill, prefs.preset)
        // Unknown id dropped from order.
        assertEquals(listOf(OverlayId.Resolution), prefs.order)
        // Invalid accent dropped.
        assertNull(prefs.items[OverlayId.Resolution]?.accentColor)
        // Valid accent + showIcon preserved.
        val imdb = prefs.items[OverlayId.RatingImdb]
        assertEquals("#abcdef", imdb?.accentColor)
        assertEquals(true, imdb?.showIcon)
        // Unknown overlay id did not create an entry.
        assertEquals(OverlayRegistry.all.size, prefs.items.size)
    }

    @Test
    fun parseV1_migratesFlatDocument() {
        // V1 has no `version` key; it's a flat map of id -> {enabled, position}.
        val raw = """
            {
              "resolution": {"enabled": false, "position": "bottom-right"},
              "rating_imdb": {"enabled": true, "position": "top-right"}
            }
        """.trimIndent()
        val prefs = OverlaySchema.parse(raw)
        assertEquals(CardOverlayPrefs.CURRENT_VERSION, prefs.version)
        assertEquals(false, prefs.items[OverlayId.Resolution]?.enabled)
        assertEquals(OverlayPosition.BottomRight, prefs.items[OverlayId.Resolution]?.position)
        assertEquals(true, prefs.items[OverlayId.RatingImdb]?.enabled)
        // Untouched overlays keep registry defaults.
        assertEquals(
            OverlayRegistry.def(OverlayId.Hdr)?.defaultEnabled,
            prefs.items[OverlayId.Hdr]?.enabled,
        )
    }
}
