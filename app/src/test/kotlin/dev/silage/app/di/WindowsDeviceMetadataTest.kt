package dev.silage.app.di

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsDeviceMetadataTest {
    @Test fun deviceReportsItsActualPlatformAndRetainsItsId() = runTest {
        val dir = Files.createTempDirectory("silage-metadata-test")
        try {
            val provider = DesktopDeviceMetadataProvider(dir.resolve("id"))
            assertEquals(if (System.getProperty("os.name").startsWith("Windows")) "windows" else "linux", provider.current().platform)
            assertEquals(provider.current().id, DesktopDeviceMetadataProvider(dir.resolve("id")).current().id)
        } finally { dir.toFile().deleteRecursively() }
    }
}
