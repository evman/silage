package dev.silage.app.shell

import dev.silage.app.ui.SidebarLibraryPref
import org.siloserver.silo.model.personal.UserLibrary

enum class LibraryKind { Movies, Shows, Music, Other }

data class SidebarLibrary(val id: Int, val name: String, val kind: LibraryKind, val visible: Boolean)

object SidebarEntries {
    /**
     * The sidebar's libraries: the user's saved order and visibility for the
     * libraries that still exist, then any new ones (visible) in server order.
     */
    fun libraries(server: List<UserLibrary>, prefs: List<SidebarLibraryPref>): List<SidebarLibrary> {
        val byId = server.associateBy { it.id }
        val saved = prefs.distinctBy { it.id }.mapNotNull { p -> byId[p.id]?.let { it to p.visible } }
        val savedIds = saved.map { it.first.id }.toSet()
        val fresh = server.filter { it.id !in savedIds }.sortedBy { it.sortOrder }.map { it to true }
        return (saved + fresh).map { (lib, visible) -> SidebarLibrary(lib.id, lib.name, kindOf(lib.type), visible) }
    }

    fun kindOf(type: String): LibraryKind = when (type.lowercase()) {
        "movie", "movies" -> LibraryKind.Movies
        "series", "show", "shows", "tv", "tvshows" -> LibraryKind.Shows
        "music" -> LibraryKind.Music
        else -> LibraryKind.Other
    }
}
