package dev.silage.player

import kotlin.test.*

class WindowsMpvHintTest {
    @Test fun windowsRuntimeHintDoesNotRecommendLinuxDistribution() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val method = MpvRuntime::class.java.getDeclaredMethod("installHint")
        method.isAccessible = true
        val hint = method.invoke(MpvRuntime) as String
        assertFalse(hint.contains("distribution"))
        assertTrue(hint.contains("libmpv-2.dll"))
    }
}
