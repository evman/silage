package dev.silage.app.profiles

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.ProfileCommitResult

data class ProfileRow(val id: String, val name: String, val hasPin: Boolean, val avatarUrl: String? = null, val avatarKey: String? = null)

sealed interface ProfilesState {
    data object Loading : ProfilesState
    data class Loaded(val profiles: List<ProfileRow>) : ProfilesState
    data class NeedsPin(val profile: ProfileRow) : ProfilesState
    data class PinError(val message: String) : ProfilesState
    data object Selected : ProfilesState
    data class Error(val message: String) : ProfilesState
}

/** [verifyPin] returns the profile token, or null when the PIN is wrong. */
class ProfilesPresenter(
    private val list: suspend () -> ApiResult<List<ProfileRow>>,
    private val verifyPin: suspend (profileId: String, pin: String) -> ApiResult<String?>,
    private val select: suspend (profileId: String, token: String?) -> ProfileCommitResult,
) {
    suspend fun load(): ProfilesState = when (val r = list()) {
        is ApiResult.Success -> ProfilesState.Loaded(r.data)
        is ApiResult.Error -> ProfilesState.Error("Couldn't load profiles: ${r.message}")
        is ApiResult.NetworkError -> ProfilesState.Error("Can't reach the server. Try again.")
    }

    suspend fun choose(profile: ProfileRow, pin: String?): ProfilesState {
        var token: String? = null
        if (profile.hasPin) {
            if (pin.isNullOrEmpty()) return ProfilesState.NeedsPin(profile)
            token = when (val r = verifyPin(profile.id, pin)) {
                is ApiResult.Success -> r.data ?: return ProfilesState.PinError("Wrong PIN.")
                is ApiResult.Error -> return ProfilesState.PinError("Couldn't check PIN: ${r.message}")
                is ApiResult.NetworkError -> return ProfilesState.PinError("Can't reach the server. Try again.")
            }
        }
        return when (select(profile.id, token)) {
            ProfileCommitResult.Committed -> ProfilesState.Selected
            else -> ProfilesState.Error("Couldn't switch profile. Try again.")
        }
    }
}
