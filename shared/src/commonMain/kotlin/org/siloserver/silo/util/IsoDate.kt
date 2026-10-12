package org.siloserver.silo.util

/**
 * Minimal proleptic-Gregorian day arithmetic over ISO "YYYY-MM-DD" strings.
 *
 * The shared module deliberately has no kotlinx-datetime dependency; the
 * calendar feature only needs day-of-week and plus/minus-days math, so the
 * classic civil-date <-> epoch-day algorithms (Howard Hinnant's
 * days_from_civil / civil_from_days) keep this dependency-free and fully
 * testable in commonTest.
 */
object IsoDate {

    /** Days since 1970-01-01 for an ISO "YYYY-MM-DD" string. */
    fun toEpochDay(iso: String): Long {
        val parts = iso.split("-")
        val y = parts[0].toInt()
        val m = parts[1].toInt()
        val d = parts[2].toInt()
        val yAdj = if (m <= 2) y - 1 else y
        val era = (if (yAdj >= 0) yAdj else yAdj - 399) / 400
        val yoe = yAdj - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** ISO "YYYY-MM-DD" for days since 1970-01-01. */
    fun fromEpochDay(epochDay: Long): String {
        val z = epochDay + 719468L
        val era = (if (z >= 0) z else z - 146096L) / 146097L
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (if (m <= 2) y + 1 else y).toInt()
        return "${pad(year, 4)}-${pad(m, 2)}-${pad(d, 2)}"
    }

    fun plusDays(iso: String, days: Long): String = fromEpochDay(toEpochDay(iso) + days)

    /** ISO-8601 day of week: Monday = 1 .. Sunday = 7. 1970-01-01 was a Thursday. */
    fun isoDayOfWeek(iso: String): Int {
        val epochDay = toEpochDay(iso)
        return ((((epochDay + 3) % 7) + 7) % 7 + 1).toInt()
    }

    /** The Monday of the week containing [iso]. */
    fun weekStart(iso: String): String = plusDays(iso, -(isoDayOfWeek(iso) - 1).toLong())

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
