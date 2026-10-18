package dev.silage.app.playback

import dev.silage.app.ui.UiPrefs
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleStyleTest {
    @Test fun defaultsMatchMpvsOwnLook() {
        val o = SubtitleStyle().mpvOptions()
        assertEquals("40", o["sub-font-size"])
        assertEquals("#FFFFFF", o["sub-color"])
        assertEquals("outline-and-shadow", o["sub-border-style"])
        assertEquals("100", o["sub-pos"])
        assertEquals("bottom", o["sub-align-y"])
    }

    @Test fun sizesScaleFromSilosPointSizes() {
        val sizes = SubtitleFontSizePreset.entries.map { SubtitleStyle(size = it).mpvOptions()["sub-font-size"] }
        assertEquals(listOf("26", "32", "40", "49", "59"), sizes)
    }

    @Test fun backgroundsAndPositions() {
        val box = SubtitleStyle(background = SubtitleBackgroundStylePreset.Box).mpvOptions()
        assertEquals("background-box", box["sub-border-style"])
        assertEquals("#BF000000", box["sub-back-color"])
        assertEquals("3", SubtitleStyle(background = SubtitleBackgroundStylePreset.Outline).mpvOptions()["sub-border-size"])
        val none = SubtitleStyle(background = SubtitleBackgroundStylePreset.None).mpvOptions()
        assertEquals("0", none["sub-border-size"]); assertEquals("0", none["sub-shadow-offset"])
        assertEquals("top", SubtitleStyle(position = SubtitlePositionPreset.Top).mpvOptions()["sub-align-y"])
        assertEquals("82", SubtitleStyle(position = SubtitlePositionPreset.LowerThird).mpvOptions()["sub-pos"])
        assertEquals("#FFE14D", SubtitleStyle(color = SubtitleColor.Yellow).mpvOptions()["sub-color"])
    }

    @Test fun unknownStoredValuesFallBackToDefaults() {
        assertEquals(SubtitleStyle(), SubtitleStyle.of("huge", "neon", null, "pink"))
        assertEquals(SubtitleStyle(SubtitleFontSizePreset.Small, SubtitleBackgroundStylePreset.Box, SubtitlePositionPreset.Top, SubtitleColor.Yellow),
            SubtitleStyle.of("small", "box", "top", "yellow"))
    }

    @Test fun storedInUiPrefs() {
        val f = Files.createTempDirectory("silage-ui").resolve("ui.json")
        assertEquals(SubtitleStyle(), UiPrefs(f).subtitleStyle)
        val style = SubtitleStyle(SubtitleFontSizePreset.XLarge, SubtitleBackgroundStylePreset.Outline, SubtitlePositionPreset.LowerThird, SubtitleColor.Yellow)
        UiPrefs(f).subtitleStyle = style
        assertEquals(style, UiPrefs(f).subtitleStyle)
        UiPrefs(f).hdrMode = "off"
        assertEquals(style, UiPrefs(f).subtitleStyle)
    }
}
