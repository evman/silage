package dev.silage.app.settings

import dev.silage.app.shell.SidebarLibrary
import dev.silage.app.ui.SidebarLibraryPref

/** Edits to the sidebar's libraries; results always list every library, in order. */
object SidebarEditor {
    fun toggle(all: List<SidebarLibrary>, id: Int): List<SidebarLibraryPref> =
        all.map { SidebarLibraryPref(it.id, if (it.id == id) !it.visible else it.visible) }

    fun move(all: List<SidebarLibrary>, id: Int, delta: Int): List<SidebarLibraryPref> {
        val list = all.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from >= 0 && to in list.indices) list.add(to, list.removeAt(from))
        return list.map { SidebarLibraryPref(it.id, it.visible) }
    }
}
