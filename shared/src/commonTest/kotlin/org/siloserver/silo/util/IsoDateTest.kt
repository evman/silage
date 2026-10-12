package org.siloserver.silo.util

import kotlin.test.Test
import kotlin.test.assertEquals

class IsoDateTest {

    @Test
    fun `epoch day round trips`() {
        assertEquals(0L, IsoDate.toEpochDay("1970-01-01"))
        assertEquals("1970-01-01", IsoDate.fromEpochDay(0L))
        assertEquals("2026-06-12", IsoDate.fromEpochDay(IsoDate.toEpochDay("2026-06-12")))
    }

    @Test
    fun `plusDays crosses month leap and year boundaries`() {
        assertEquals("2024-02-29", IsoDate.plusDays("2024-02-28", 1))
        assertEquals("2026-01-05", IsoDate.plusDays("2025-12-29", 7))
        assertEquals("2025-12-29", IsoDate.plusDays("2026-01-05", -7))
    }

    @Test
    fun `week starts on monday`() {
        // 2026-06-12 is a Friday; 1970-01-01 was a Thursday.
        assertEquals(4, IsoDate.isoDayOfWeek("1970-01-01"))
        assertEquals(5, IsoDate.isoDayOfWeek("2026-06-12"))
        assertEquals("2026-06-08", IsoDate.weekStart("2026-06-12"))
        assertEquals("2026-06-08", IsoDate.weekStart("2026-06-08")) // Monday is its own week start
        assertEquals("2026-06-08", IsoDate.weekStart("2026-06-14")) // Sunday belongs to the preceding Monday
    }
}
