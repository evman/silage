package dev.silage.app.calendar

import dev.silage.app.ui.UiPrefs
import org.siloserver.silo.model.calendar.CalendarItem
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarLabelsTest {
    @Test fun dayLabelsNameTodayTomorrowAndYesterday() {
        assertEquals("Today · Sunday 18 October", calendarDayLabel("2026-10-18", "2026-10-18"))
        assertEquals("Tomorrow · Monday 19 October", calendarDayLabel("2026-10-19", "2026-10-18"))
        assertEquals("Yesterday · Saturday 17 October", calendarDayLabel("2026-10-17", "2026-10-18"))
        assertEquals("Wednesday 21 October", calendarDayLabel("2026-10-21", "2026-10-18"))
        assertEquals("not-a-date", calendarDayLabel("not-a-date", "2026-10-18"))
    }

    @Test fun weekLabelsShortenWithinAMonth() {
        assertEquals("12 – 18 October 2026", calendarWeekLabel("2026-10-12", "2026-10-18"))
        assertEquals("28 September – 4 October 2026", calendarWeekLabel("2026-09-28", "2026-10-04"))
        assertEquals("28 December 2026 – 3 January 2027", calendarWeekLabel("2026-12-28", "2027-01-03"))
        assertEquals("", calendarWeekLabel("", ""))
    }

    private fun item(airAt: String? = null, airTime: String? = null) =
        CalendarItem(contentId = "c", type = "episode", title = "Show", airDate = "2026-10-18", localAirDate = "2026-10-18", airAt = airAt, airTime = airTime)

    @Test fun airTimesAreShownInThisComputersTimeZone() {
        val london = ZoneId.of("Europe/London")
        assertEquals("2:00 AM", calendarAirTime(item(airAt = "2026-10-18T01:00:00Z"), london), "BST is UTC+1")
        assertEquals("9:00 PM", calendarAirTime(item(airTime = "9:00 PM"), london), "falls back to the server's text")
        assertNull(calendarAirTime(item(), london))
    }

    @Test fun theChosenFilterIsRememberedInUiPrefs() {
        val prefs = UiPrefs(Files.createTempDirectory("silage-ui").resolve("ui.json"))
        val store = UiPrefsCalendarFilterStore(prefs)
        assertNull(store.read())
        store.write("trending")
        assertEquals("trending", store.read())
    }
}
