package dev.silage.app.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class SidebarLibraryPref(val id: Int, val visible: Boolean)

@Serializable
private data class UiPrefsFile(
    val sidebar: List<SidebarLibraryPref> = emptyList(),
    /** auto (detect the monitor's HDR), on, or off. */
    val hdrMode: String = "auto",
    /** Where the main window was left (Windows). */
    val window: dev.silage.app.SavedWindow? = null,
)

/** Device-only interface preferences in `ui.json`; unreadable files fall back to defaults. */
class UiPrefs(private val path: Path) {
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumped on every write, so screens showing a preference can refresh. */
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = _changes

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun read(): UiPrefsFile = runCatching { json.decodeFromString(UiPrefsFile.serializer(), Files.readString(path)) }
        .getOrDefault(UiPrefsFile())

    private fun write(update: (UiPrefsFile) -> UiPrefsFile): Unit = synchronized(this) {
        val next = update(read())
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, "ui", ".tmp")
        Files.writeString(tmp, json.encodeToString(UiPrefsFile.serializer(), next))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _changes.value++
        Unit
    }

    var sidebar: List<SidebarLibraryPref>
        get() = read().sidebar
        set(value) = write { it.copy(sidebar = value) }

    var hdrMode: String
        get() = read().hdrMode
        set(value) = write { it.copy(hdrMode = value) }

    var window: dev.silage.app.SavedWindow?
        get() = read().window
        set(value) = write { it.copy(window = value) }
}
