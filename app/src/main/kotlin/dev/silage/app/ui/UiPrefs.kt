package dev.silage.app.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class SidebarLibraryPref(val id: Int, val visible: Boolean)

/**
 * A library page's sort and filters as last chosen, in wire names (sort key,
 * "asc"/"desc", facet and duration enum names) so values a later version
 * drops are skipped rather than failing to load.
 */
@Serializable
data class SavedLibraryView(
    val sort: String? = null,
    val order: String? = null,
    val matchAll: Boolean = true,
    val filters: Map<String, List<String>> = emptyMap(),
    val durations: List<String> = emptyList(),
)

@Serializable
private data class UiPrefsFile(
    val sidebar: List<SidebarLibraryPref> = emptyList(),
    /** auto (detect the monitor's HDR), on, or off. */
    val hdrMode: String = "auto",
    val experimentalDolbyVision: Boolean = false,
    /** Where the main window was left (Windows). */
    val window: dev.silage.app.SavedWindow? = null,
    /** Open Silage as a borderless fullscreen window (Windows) instead of a normal one. */
    val fullscreenStart: Boolean = false,
    /** Play the next episode after the "Up next" countdown. */
    val autoplayNext: Boolean = true,
    /** Jump past intro markers without pressing Skip Intro. */
    val autoSkipIntro: Boolean = false,
    /** The calendar's Following / Trending / All choice. */
    val calendarFilter: String? = null,
    /** Subtitle look, by Silo preset wire names (see SubtitleStyle). */
    val subtitleSize: String? = null,
    val subtitleBackground: String? = null,
    val subtitlePosition: String? = null,
    val subtitleColor: String? = null,
    /** Without a copy chosen: "best" quality, or the "last" one played. */
    val preferredVersion: String = "best",
    /** How big the interface is drawn: see [InterfaceSize]. */
    val interfaceSize: String = InterfaceSize.Default.wire,
    /** Jump past recap markers ("Previously on…") without pressing Skip Recap. */
    val autoSkipRecap: Boolean = false,
    /** At the credits marker, go to the next episode (or past the credits) by itself. */
    val autoSkipCredits: Boolean = false,
    /** mpv's audio device name ("wasapi/{…}"); null plays through the Windows default. */
    val audioDevice: String? = null,
    /** Two-letter language for the audio track; null keeps the server's / file's choice. */
    val audioLanguage: String? = null,
    /** Subtitles at the start: null (the server's choice for the profile), off, on, forced. */
    val subtitleMode: String? = null,
    /** Two-letter language for subtitles when [subtitleMode] picks them; null follows the audio language. */
    val subtitleLanguage: String? = null,
    /** Streaming quality to ask the server for: "auto", or a resolution such as "1080p" (see [dev.silage.app.playback.QualityPrefs]). */
    val qualityPreference: String = "auto",
    /** The most the server may send, in kbps; null for no limit. */
    val maxBitrateKbps: Int? = null,
    /** The player's volume (0–130) and mute, as last set with the keyboard; null volume is mpv's default. */
    val playerVolume: Int? = null,
    val playerMuted: Boolean = false,
    /** The last few searches, newest first. */
    val recentSearches: List<String> = emptyList(),
    /**
     * Each library's [SavedLibraryView], by library id. Kept as raw JSON and
     * read entry by entry, so one bad entry doesn't lose the whole file.
     */
    val libraryViews: JsonElement? = null,
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

    var experimentalDolbyVision: Boolean
        get() = read().experimentalDolbyVision
        set(value) = write { it.copy(experimentalDolbyVision = value) }

    var window: dev.silage.app.SavedWindow?
        get() = read().window
        set(value) = write { it.copy(window = value) }

    var fullscreenStart: Boolean
        get() = read().fullscreenStart
        set(value) = write { it.copy(fullscreenStart = value) }

    var autoplayNext: Boolean
        get() = read().autoplayNext
        set(value) = write { it.copy(autoplayNext = value) }

    var autoSkipIntro: Boolean
        get() = read().autoSkipIntro
        set(value) = write { it.copy(autoSkipIntro = value) }

    var autoSkipRecap: Boolean
        get() = read().autoSkipRecap
        set(value) = write { it.copy(autoSkipRecap = value) }

    var autoSkipCredits: Boolean
        get() = read().autoSkipCredits
        set(value) = write { it.copy(autoSkipCredits = value) }

    var audioDevice: String?
        get() = read().audioDevice
        set(value) = write { it.copy(audioDevice = value) }

    /** Default audio and subtitle choices for new videos (Settings → Playback / Subtitles). */
    var languages: dev.silage.app.playback.LanguagePrefs
        get() = read().let { dev.silage.app.playback.LanguagePrefs(it.audioLanguage, it.subtitleMode, it.subtitleLanguage) }
        set(value) = write { it.copy(audioLanguage = value.audio, subtitleMode = value.subtitleMode, subtitleLanguage = value.subtitle) }

    var calendarFilter: String?
        get() = read().calendarFilter
        set(value) = write { it.copy(calendarFilter = value) }

    var interfaceSize: InterfaceSize
        get() = InterfaceSize.of(read().interfaceSize)
        set(value) = write { it.copy(interfaceSize = value.wire) }

    var preferredVersion: String
        get() = read().preferredVersion
        set(value) = write { it.copy(preferredVersion = value) }

    /** Volume and mute carried from one video to the next. */
    var playerVolume: Pair<Int?, Boolean>
        get() = read().let { it.playerVolume to it.playerMuted }
        set(value) = write { it.copy(playerVolume = value.first?.coerceIn(0, 130), playerMuted = value.second) }

    /** Searches typed in Search, newest first (at most [RecentSearches.Max]). */
    var recentSearches: List<String>
        get() = read().recentSearches
        set(value) = write { it.copy(recentSearches = value) }

    /** Streaming quality and bitrate limit (Settings → Video). */
    var quality: dev.silage.app.playback.QualityPrefs
        get() = read().let { dev.silage.app.playback.QualityPrefs(it.qualityPreference, it.maxBitrateKbps) }
        set(value) = write { it.copy(qualityPreference = value.preference, maxBitrateKbps = value.maxBitrateKbps) }

    /** The sort and filters last chosen on [libraryId]'s page; null if none (or unreadable). */
    fun libraryView(libraryId: Int): SavedLibraryView? =
        (read().libraryViews as? JsonObject)?.get(libraryId.toString())?.let { e ->
            runCatching { json.decodeFromJsonElement(SavedLibraryView.serializer(), e) }.getOrNull()
        }

    fun setLibraryView(libraryId: Int, view: SavedLibraryView) = write {
        val others = (it.libraryViews as? JsonObject).orEmpty()
        it.copy(libraryViews = JsonObject(others + (libraryId.toString() to json.encodeToJsonElement(SavedLibraryView.serializer(), view))))
    }

    var subtitleStyle: dev.silage.app.playback.SubtitleStyle
        get() = read().let { dev.silage.app.playback.SubtitleStyle.of(it.subtitleSize, it.subtitleBackground, it.subtitlePosition, it.subtitleColor) }
        set(value) = write {
            it.copy(subtitleSize = value.size.wire, subtitleBackground = value.background.wire,
                subtitlePosition = value.position.wire, subtitleColor = value.color.wire)
        }
}

/**
 * The interface's size relative to the 1080p layout fitted to the window:
 * Large is the original TV-style size; the others fit more on screen, as
 * desktop apps do.
 */
enum class InterfaceSize(val wire: String, val label: String, val factor: Float) {
    Small("small", "Small", 0.72f),
    Medium("medium", "Medium", 0.85f),
    Large("large", "Large", 1.0f),
    ;

    /** One step smaller or larger (Ctrl − / Ctrl +), stopping at the ends. */
    fun step(by: Int): InterfaceSize = entries[(ordinal + by).coerceIn(0, entries.lastIndex)]

    companion object {
        val Default = Medium
        fun of(wire: String?): InterfaceSize = entries.firstOrNull { it.wire == wire } ?: Default
    }
}
