package dev.silage.player

import com.sun.jna.NativeLibrary
import java.io.File

/** mpv imports the Vulkan loader even when video uses D3D11. */
internal object WindowsPlaybackDependencies {
    // Keep the library alive for mpv's whole lifetime. Prefer the driver's system
    // loader; use the bundled copy only on machines without one (e.g. clean VMs).
    private val vulkan: NativeLibrary? by lazy {
        runCatching { NativeLibrary.getInstance("vulkan-1") }.getOrNull()
            ?: bundledCandidates().firstNotNullOfOrNull { file ->
                if (!file.isFile) null else runCatching { NativeLibrary.getInstance(file.absolutePath) }.getOrNull()
            }
    }

    fun prepare() { vulkan }

    private fun bundledCandidates(): List<File> {
        val launcherDirectory = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile }
        val sourceDirectories = System.getProperty("jna.library.path").orEmpty()
            .split(File.pathSeparator).filter(String::isNotBlank).map(::File)
        return (listOfNotNull(launcherDirectory) + sourceDirectories).map { it.resolve("fallback/vulkan-1.dll") }
    }
}
