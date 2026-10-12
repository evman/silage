package dev.silage.app

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    /** The version comes from the build (-PappVersion, from the release tag). */
    @Test fun versionIsSemverLike() {
        assertTrue(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""").matches(BuildInfo.VERSION), BuildInfo.VERSION)
        assertTrue(BuildInfo.NAME == "Silage")
    }
}
