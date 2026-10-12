package org.siloserver.silo.model.catalog

import org.siloserver.silo.util.IsoDate

data class PersonWorksFilter(
    val key: String,
    val title: String,
    val serverMediaType: String?,
    val clientTypes: Set<String> = emptySet(),
)

private val monthNames = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val readingTypes = setOf(
    "book",
    "books",
    "ebook",
    "ebooks",
    "comic",
    "comics",
    "manga",
    "reading",
)

fun personWorksFiltersForMobile(): List<PersonWorksFilter> = listOf(
    PersonWorksFilter(key = "all", title = "All", serverMediaType = null),
    PersonWorksFilter(key = "movie", title = "Movies", serverMediaType = "movie"),
    PersonWorksFilter(key = "series", title = "TV", serverMediaType = "series"),
    PersonWorksFilter(key = "audiobook", title = "Audiobooks", serverMediaType = "audiobook"),
    PersonWorksFilter(key = "music", title = "Music", serverMediaType = "music"),
    PersonWorksFilter(key = "reading", title = "Reading", serverMediaType = null, clientTypes = readingTypes),
)

fun personWorksFiltersForTv(): List<PersonWorksFilter> = listOf(
    PersonWorksFilter(key = "all", title = "All", serverMediaType = null),
    PersonWorksFilter(key = "movie", title = "Movies", serverMediaType = "movie"),
    PersonWorksFilter(key = "series", title = "TV", serverMediaType = "series"),
    PersonWorksFilter(key = "audiobook", title = "Audiobooks", serverMediaType = "audiobook"),
    PersonWorksFilter(key = "music", title = "Music", serverMediaType = "music"),
)

fun isReadingMediaType(type: String?): Boolean =
    type?.trim()?.lowercase() in readingTypes

fun personInitials(name: String): String {
    val initials = name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
    return initials.ifBlank { "?" }
}

fun personMetadataBadges(person: Person, todayIso: String? = null): List<String> {
    val badges = mutableListOf<String>()
    formattedPersonDate(person.birthDate)?.let { badges += "Born $it" }
    if (person.deathDate.isNullOrBlank()) {
        personAge(person.birthDate, null, todayIso)?.let { badges += "$it years old" }
    } else {
        val deathDate = formattedPersonDate(person.deathDate)
        val age = personAge(person.birthDate, person.deathDate, todayIso)
        if (deathDate != null && age != null) {
            badges += "Died $deathDate (age $age)"
        } else if (deathDate != null) {
            badges += "Died $deathDate"
        }
    }
    person.birthplace?.trim()?.takeIf { it.isNotBlank() }?.let { badges += it }
    return badges
}

fun formattedPersonDate(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parts = value.split("-")
    if (parts.size != 3) return value
    val year = parts[0].toIntOrNull() ?: return value
    val month = parts[1].toIntOrNull() ?: return value
    val day = parts[2].toIntOrNull() ?: return value
    if (month !in 1..12 || day !in 1..31) return value
    return "${monthNames[month - 1]} $day, $year"
}

fun personAge(birthValue: String?, deathValue: String?, todayIso: String?): Int? {
    val birth = parseIsoDateParts(birthValue) ?: return null
    val end = parseIsoDateParts(deathValue) ?: parseIsoDateParts(todayIso) ?: return null
    var age = end.year - birth.year
    if (end.month < birth.month || (end.month == birth.month && end.day < birth.day)) age -= 1
    return age.takeIf { it >= 0 }
}

fun personWorksCountLabel(total: Int, loaded: Int, hasMore: Boolean): String? = when {
    total > 0 && hasMore -> "$loaded of $total titles"
    total > 0 -> if (total == 1) "1 title" else "$total titles"
    loaded > 0 && hasMore -> "$loaded+ titles"
    loaded > 0 -> if (loaded == 1) "1 title" else "$loaded titles"
    else -> null
}

private data class IsoDateParts(val year: Int, val month: Int, val day: Int)

private fun parseIsoDateParts(value: String?): IsoDateParts? {
    val raw = value?.trim()?.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
    return runCatching {
        IsoDate.toEpochDay(raw)
        val parts = raw.split("-")
        IsoDateParts(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }.getOrNull()
}
