package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonPresentationTest {
    @Test
    fun metadataBadgesFormatDatesAgeAndBirthplace() {
        val person = Person(
            id = 1,
            name = "Andy Weir",
            birthDate = "1972-06-16",
            birthplace = "Davis, California",
        )

        assertEquals(
            listOf("Born Jun 16, 1972", "54 years old", "Davis, California"),
            personMetadataBadges(person, todayIso = "2026-06-19"),
        )
    }

    @Test
    fun metadataBadgesFormatDeathAge() {
        val person = Person(
            id = 2,
            name = "Example Person",
            birthDate = "1950-01-10",
            deathDate = "2020-01-09",
        )

        assertEquals(
            listOf("Born Jan 10, 1950", "Died Jan 9, 2020 (age 69)"),
            personMetadataBadges(person, todayIso = "2026-06-19"),
        )
    }

    @Test
    fun initialsUseFirstTwoNameParts() {
        assertEquals("AW", personInitials("Andy Weir"))
        assertEquals("P", personInitials("Prince"))
        assertEquals("?", personInitials("   "))
    }

    @Test
    fun worksCountLabelDistinguishesLoadedTotalAndMore() {
        assertEquals("12 titles", personWorksCountLabel(total = 12, loaded = 12, hasMore = false))
        assertEquals("60 of 120 titles", personWorksCountLabel(total = 120, loaded = 60, hasMore = true))
        assertEquals("60+ titles", personWorksCountLabel(total = 0, loaded = 60, hasMore = true))
        assertEquals(null, personWorksCountLabel(total = 0, loaded = 0, hasMore = false))
    }

    @Test
    fun tvFiltersExcludeReading() {
        assertTrue(personWorksFiltersForMobile().any { it.key == "reading" })
        assertFalse(personWorksFiltersForTv().any { it.key == "reading" })
        assertTrue(isReadingMediaType("ebook"))
        assertTrue(isReadingMediaType("comic"))
        assertTrue(isReadingMediaType("manga"))
        assertFalse(isReadingMediaType("audiobook"))
    }
}
