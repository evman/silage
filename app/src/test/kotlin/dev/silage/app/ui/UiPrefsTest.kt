package dev.silage.app.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class UiPrefsTest {
    @Test fun roundTripsAndSurvivesACorruptFile() {
        val f = Files.createTempDirectory("silage-ui").resolve("ui.json")
        UiPrefs(f).sidebar = listOf(SidebarLibraryPref(2, false))
        UiPrefs(f).hdrMode = "on"
        assertEquals(listOf(SidebarLibraryPref(2, false)), UiPrefs(f).sidebar)
        assertEquals("on", UiPrefs(f).hdrMode)
        Files.writeString(f, "{broken")
        assertEquals(emptyList(), UiPrefs(f).sidebar)
        assertEquals("auto", UiPrefs(f).hdrMode)
    }

    @Test fun missingFileGivesDefaults() {
        val p = UiPrefs(Files.createTempDirectory("silage-ui").resolve("nope/ui.json"))
        assertEquals(emptyList(), p.sidebar)
        assertEquals("auto", p.hdrMode)
    }
}
