package org.siloserver.silo.model.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileNameConflictTest {
    private val profiles = listOf(
        Profile(id = "p1", name = "Main", isPrimary = true),
        Profile(id = "p2", name = "Laura"),
    )

    @Test
    fun matchesCaseInsensitivelyAndTrimmed() {
        assertTrue(profiles.hasProfileNamed("laura"))
        assertTrue(profiles.hasProfileNamed(" LAURA "))
        assertTrue(profiles.hasProfileNamed("Main"))
    }

    @Test
    fun distinctNameDoesNotConflict() {
        assertFalse(profiles.hasProfileNamed("Ed"))
    }

    @Test
    fun renameExcludesTheProfileItself() {
        // Re-saving p2 under its own name is not a conflict...
        assertFalse(profiles.hasProfileNamed("Laura", excludeId = "p2"))
        // ...but renaming p2 to another profile's name is.
        assertTrue(profiles.hasProfileNamed("Main", excludeId = "p2"))
    }
}
