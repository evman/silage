package dev.silage.app.profiles

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.ProfileCommitResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProfilesPresenterTest {
    private fun presenter(
        verify: suspend (String, String) -> ApiResult<String?> = { _, _ -> ApiResult.Success("tok") },
        selected: MutableList<Pair<String, String?>> = mutableListOf(),
    ) = ProfilesPresenter(
        list = { ApiResult.Success(listOf(ProfileRow("p1", "Ann", hasPin = false), ProfileRow("p2", "Kids", hasPin = true))) },
        verifyPin = verify,
        select = { id, token -> selected += id to token; ProfileCommitResult.Committed },
    )

    @Test fun loadsProfiles() = runTest {
        assertEquals(listOf("Ann", "Kids"), (presenter().load() as ProfilesState.Loaded).profiles.map { it.name })
    }

    @Test fun selectWithoutPin() = runTest {
        val selected = mutableListOf<Pair<String, String?>>()
        assertEquals(ProfilesState.Selected, presenter(selected = selected).choose(ProfileRow("p1", "Ann", false), pin = null))
        assertEquals(listOf<Pair<String, String?>>("p1" to null), selected)
    }

    @Test fun pinProfileNeedsPin() = runTest {
        assertIs<ProfilesState.NeedsPin>(presenter().choose(ProfileRow("p2", "Kids", true), pin = null))
    }

    @Test fun correctPinSelectsWithToken() = runTest {
        val selected = mutableListOf<Pair<String, String?>>()
        assertEquals(ProfilesState.Selected, presenter(selected = selected).choose(ProfileRow("p2", "Kids", true), pin = "1234"))
        assertEquals(listOf<Pair<String, String?>>("p2" to "tok"), selected)
    }

    @Test fun wrongPin() = runTest {
        val p = presenter(verify = { _, _ -> ApiResult.Success(null) })
        assertEquals(ProfilesState.PinError("Wrong PIN."), p.choose(ProfileRow("p2", "Kids", true), pin = "0000"))
    }
}
