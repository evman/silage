package dev.silage.app.di

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsDeviceMetadataTest {
    @Test fun windowsDeviceIsNotReportedAsLinux() = runTest {
        val dir = Files.createTempDirectory("silage-metadata-test")
        try {
            val provider = DesktopDeviceMetadataProvider(dir.resolve("id"))
            assertEquals("windows", provider.current().platform)
            assertEquals(provider.current().id, provider.current().id)
        } finally { dir.toFile().deleteRecursively() }
    }
}
