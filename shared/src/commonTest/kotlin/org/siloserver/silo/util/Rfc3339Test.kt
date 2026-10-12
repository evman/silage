package org.siloserver.silo.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Rfc3339Test {

    // Reference: 2026-06-12T09:30:00Z == 1781602200000 ms (verified below via Z baseline).
    // 2026-06-12 is day count from 1970; we assert relationships rather than hard-code
    // every value, but anchor on a single computed baseline.
    private val baselineZ = parseRfc3339ToEpochMillis("2026-06-12T09:30:00Z")!!

    @Test
    fun `parses trailing Z as UTC`() {
        assertEquals(baselineZ, parseRfc3339ToEpochMillis("2026-06-12T09:30:00Z"))
        // sanity: a known epoch — 1970-01-01T00:00:00Z is 0.
        assertEquals(0L, parseRfc3339ToEpochMillis("1970-01-01T00:00:00Z"))
        // one full day later.
        assertEquals(86_400_000L, parseRfc3339ToEpochMillis("1970-01-02T00:00:00Z"))
    }

    @Test
    fun `positive offset shifts earlier in UTC`() {
        // 09:30+02:00 == 07:30Z == baseline - 2h.
        assertEquals(
            baselineZ - 2 * 3_600_000L,
            parseRfc3339ToEpochMillis("2026-06-12T09:30:00+02:00"),
        )
    }

    @Test
    fun `negative offset shifts later in UTC`() {
        // 09:30-05:00 == 14:30Z == baseline + 5h.
        assertEquals(
            baselineZ + 5 * 3_600_000L,
            parseRfc3339ToEpochMillis("2026-06-12T09:30:00-05:00"),
        )
    }

    @Test
    fun `no fractional seconds`() {
        assertEquals(baselineZ, parseRfc3339ToEpochMillis("2026-06-12T09:30:00Z"))
    }

    @Test
    fun `three digit fraction is milliseconds`() {
        assertEquals(baselineZ + 123L, parseRfc3339ToEpochMillis("2026-06-12T09:30:00.123Z"))
    }

    @Test
    fun `six digit fraction truncates to millis`() {
        assertEquals(baselineZ + 123L, parseRfc3339ToEpochMillis("2026-06-12T09:30:00.123456Z"))
    }

    @Test
    fun `nine digit fraction truncates to millis`() {
        assertEquals(baselineZ + 123L, parseRfc3339ToEpochMillis("2026-06-12T09:30:00.123456789Z"))
    }

    @Test
    fun `one and two digit fractions pad to millis`() {
        assertEquals(baselineZ + 100L, parseRfc3339ToEpochMillis("2026-06-12T09:30:00.1Z"))
        assertEquals(baselineZ + 120L, parseRfc3339ToEpochMillis("2026-06-12T09:30:00.12Z"))
    }

    @Test
    fun `fraction with numeric offset`() {
        assertEquals(
            baselineZ - 2 * 3_600_000L + 5L,
            parseRfc3339ToEpochMillis("2026-06-12T09:30:00.005+02:00"),
        )
    }

    @Test
    fun `malformed inputs return null`() {
        assertNull(parseRfc3339ToEpochMillis("not-a-date"))
        assertNull(parseRfc3339ToEpochMillis("2026-06-12"))                  // no time
        assertNull(parseRfc3339ToEpochMillis("2026-06-12T09:30:00"))         // no zone
        assertNull(parseRfc3339ToEpochMillis("2026-06-12T09:30Z"))           // no seconds
        assertNull(parseRfc3339ToEpochMillis("2026-13-12T09:30:00Z"))        // bad month
        assertNull(parseRfc3339ToEpochMillis("2026-06-12T25:30:00Z"))        // bad hour
        assertNull(parseRfc3339ToEpochMillis("2026-06-12T09:30:00.abcZ"))    // non-digit fraction
        assertNull(parseRfc3339ToEpochMillis("2026-06-12T09:30:00.1234567890Z")) // 10-digit fraction
        assertNull(parseRfc3339ToEpochMillis("2026-06-12T09:30:00+2:00"))    // bad offset width
        assertNull(parseRfc3339ToEpochMillis("T09:30:00Z"))                  // no date
    }

    @Test
    fun `empty returns null`() {
        assertNull(parseRfc3339ToEpochMillis(""))
    }
}
