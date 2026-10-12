package org.siloserver.silo.repository

import org.siloserver.silo.model.settings.LibraryPlaybackPref
import org.siloserver.silo.model.settings.LibraryPlaybackPrefRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.LibraryPlaybackPrefsApi
import org.siloserver.silo.network.map

class LibraryPlaybackPrefsRepository(
    private val api: LibraryPlaybackPrefsApi,
) {
    suspend fun list(): ApiResult<Map<Int, LibraryPlaybackPref>> =
        api.list().map { response ->
            response.preferences.associateBy { it.libraryId }
        }

    suspend fun set(
        libraryId: Int,
        audioLanguage: String?,
        subtitleLanguage: String?,
        subtitleMode: String?,
        showForcedSubtitles: Boolean?,
    ): ApiResult<Unit> = api.set(
        libraryId = libraryId,
        request = LibraryPlaybackPrefRequest(
            audioLanguage = audioLanguage,
            subtitleLanguage = subtitleLanguage,
            subtitleMode = subtitleMode,
            showForcedSubtitles = showForcedSubtitles,
        ),
    )

    suspend fun delete(libraryId: Int): ApiResult<Unit> = api.delete(libraryId)
}
