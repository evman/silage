package dev.silage.app.di

import dev.silage.app.BuildInfo
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.SiloDeviceMetadata
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Stable per-install device id, stored once in [idFile]. */
class DesktopDeviceMetadataProvider(private val idFile: Path) : DeviceMetadataProvider {
    private val id: String by lazy {
        if (Files.exists(idFile)) Files.readString(idFile).trim()
        else UUID.randomUUID().toString().also { Files.createDirectories(idFile.parent); Files.writeString(idFile, it) }
    }

    override suspend fun current(): SiloDeviceMetadata = SiloDeviceMetadata(
        id = id,
        name = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Linux HTPC"),
        platform = "linux",
        clientName = BuildInfo.NAME,
        clientVersion = BuildInfo.VERSION,
    )
}
