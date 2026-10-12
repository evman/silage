package org.siloserver.silo.domain.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import org.siloserver.silo.repository.port.CatalogCacheScope

/**
 * Bumps when the active profile's metadata language changes on this device.
 *
 * The server localizes titles and descriptions per profile, so every catalog
 * list on screen holds text in the old language until it is read again. Home,
 * For You and library surfaces collect this and re-read quietly; a profile
 * switch needs no signal because it rebuilds those screens.
 */
class CatalogLanguageRevision {
    internal data class Changes(
        val revision: Long = 0L,
        val scopes: Map<CatalogCacheScope?, Long> = emptyMap(),
    )

    private val _changes = MutableStateFlow(Changes())
    internal val changes: Changes get() = _changes.value
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun changed(scope: CatalogCacheScope? = null) {
        val next = _changes.updateAndGet { current ->
            val revision = current.revision + 1
            Changes(revision, current.scopes + (scope to revision))
        }
        _revision.update { maxOf(it, next.revision) }
    }
}
