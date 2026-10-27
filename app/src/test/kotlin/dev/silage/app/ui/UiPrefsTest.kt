package dev.silage.app.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class UiPrefsTest {
    @Test fun roundTripsAndSurvivesACorruptFile() {
        val f = Files.createTempDirectory("silage-ui").resolve("ui.json")
        UiPrefs(f).sidebar = listOf(SidebarLibraryPref(2, false))
        UiPrefs(f).hdrMode = "on"
        UiPrefs(f).experimentalDolbyVision = true
        assertEquals(listOf(SidebarLibraryPref(2, false)), UiPrefs(f).sidebar)
        assertEquals("on", UiPrefs(f).hdrMode)
        assertEquals(true, UiPrefs(f).experimentalDolbyVision)
        Files.writeString(f, "{broken")
        assertEquals(emptyList(), UiPrefs(f).sidebar)
        assertEquals("auto", UiPrefs(f).hdrMode)
        assertEquals(false, UiPrefs(f).experimentalDolbyVision)
    }

    @Test fun missingFileGivesDefaults() {
        val p = UiPrefs(Files.createTempDirectory("silage-ui").resolve("nope/ui.json"))
        assertEquals(emptyList(), p.sidebar)
        assertEquals("auto", p.hdrMode)
    }

    @Test fun libraryViewsRoundTripPerLibrary() {
        val f = Files.createTempDirectory("silage-ui").resolve("ui.json")
        val view = SavedLibraryView(sort = "title", order = "asc", matchAll = false,
            filters = mapOf("Genre" to listOf("Drama")), durations = listOf("OverTwoHours"))
        UiPrefs(f).setLibraryView(3, view)
        UiPrefs(f).hdrMode = "on"
        assertEquals(view, UiPrefs(f).libraryView(3))
        assertEquals(null, UiPrefs(f).libraryView(4))
        assertEquals("on", UiPrefs(f).hdrMode)
    }

    @Test fun oldFileWithoutLibraryViewsLoads() {
        val f = Files.createTempDirectory("silage-ui").resolve("ui.json")
        Files.writeString(f, """{"hdrMode":"on","sidebar":[{"id":2,"visible":false}]}""")
        assertEquals(null, UiPrefs(f).libraryView(1))
        assertEquals("on", UiPrefs(f).hdrMode)
        assertEquals(listOf(SidebarLibraryPref(2, false)), UiPrefs(f).sidebar)
    }

    @Test fun aCorruptLibraryViewKeepsTheRestOfTheFile() {
        val f = Files.createTempDirectory("silage-ui").resolve("ui.json")
        Files.writeString(f, """{"hdrMode":"on","libraryViews":{"1":"garbage","2":{"sort":"year","matchAll":"nope"},"3":{"sort":"title"}}}""")
        val p = UiPrefs(f)
        assertEquals("on", p.hdrMode)
        assertEquals(null, p.libraryView(1))
        assertEquals(null, p.libraryView(2))
        assertEquals(SavedLibraryView(sort = "title"), p.libraryView(3))
        Files.writeString(f, """{"hdrMode":"on","libraryViews":42}""")
        assertEquals("on", UiPrefs(f).hdrMode)
        assertEquals(null, UiPrefs(f).libraryView(1))
        // Saving over a corrupt entry replaces it and keeps the others.
        Files.writeString(f, """{"libraryViews":{"1":"garbage","3":{"sort":"title"}}}""")
        UiPrefs(f).setLibraryView(1, SavedLibraryView(sort = "year"))
        assertEquals(SavedLibraryView(sort = "year"), UiPrefs(f).libraryView(1))
        assertEquals(SavedLibraryView(sort = "title"), UiPrefs(f).libraryView(3))
    }
}
