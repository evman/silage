package dev.silage.app.playback

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DvHelperPackagingTest {
    @Test fun persistedOptInAndDeveloperSwitchDoNotEnableOtherPlatforms() {
        assertTrue(dvHelperRequested(true, null, "Linux"))
        assertFalse(dvHelperRequested(false, null, "Linux"))
        assertTrue(dvHelperRequested(false, "helper", "Linux"))
        assertFalse(dvHelperRequested(true, "off", "Linux"))
        assertFalse(dvHelperRequested(true, "compositor", "Linux"))
        assertFalse(dvHelperRequested(true, "helper", "Windows 11"))
    }

    @Test fun portableBootstrapRunsOnlyForMissingOrChangedInstallation() {
        val root = Files.createTempDirectory("silage-helper-package").toFile()
        try {
            val bundled = File(root, "bundle").apply { mkdir() }
            val installed = File(root, "installed").apply { mkdir() }
            val policy = File(root, "policy")
            listOf("silage-dv-helper", "kms-snapshot", "dev.silage.dv-helper.policy").forEach {
                File(bundled, it).writeText(it)
            }
            val setup = File(bundled, "setup-helper").absolutePath
            assertEquals(setup, dvHelperProgram(bundled, installed, policy))
            listOf("silage-dv-helper", "kms-snapshot").forEach {
                File(bundled, it).copyTo(File(installed, it)); File(installed, it).setExecutable(true)
            }
            File(bundled, "dev.silage.dv-helper.policy").copyTo(policy)
            assertEquals(File(installed, "silage-dv-helper").absolutePath, dvHelperProgram(bundled, installed, policy))
            File(bundled, "silage-dv-helper").appendText(" updated")
            assertEquals(setup, dvHelperProgram(bundled, installed, policy))
        } finally { root.deleteRecursively() }
    }
}
