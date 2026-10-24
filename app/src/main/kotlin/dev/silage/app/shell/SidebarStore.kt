package dev.silage.app.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.siloserver.silo.model.personal.UserLibrary

/**
 * The sidebar's profile and libraries, kept for the whole session. Every page builds its own
 * sidebar, so without this each one started empty (a "?" for the picture, no name) until its
 * own request came back. Each page still asks again and shows any change.
 */
class SidebarStore {
    private val _profile = MutableStateFlow<SidebarProfile?>(null)
    val profile: StateFlow<SidebarProfile?> = _profile
    private val _libraries = MutableStateFlow<List<UserLibrary>?>(null)
    /** Null until loaded once, so an empty list means the server has none. */
    val libraries: StateFlow<List<UserLibrary>?> = _libraries

    fun setProfile(profile: SidebarProfile) { _profile.value = profile }
    fun setLibraries(libraries: List<UserLibrary>) { _libraries.value = libraries }

    /** Signing out or changing profile: the next profile's sidebar starts fresh. */
    fun clear() { _profile.value = null; _libraries.value = null }
}
