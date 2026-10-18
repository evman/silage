package dev.silage.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import dev.silage.app.profiles.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.ProfileCommitResult
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AvatarFallbackTest {
    @Test fun absentImageShowsInitial() = runComposeUiTest {
        setContent { MaterialTheme { ProfileAvatar("Nova", null) } }
        onNodeWithText("N").assertIsDisplayed()
    }

    @Test fun blankUrlAndEmptyNameHaveVisibleFallback() = runComposeUiTest {
        setContent { MaterialTheme { ProfileAvatar("", "  ") } }
        onNodeWithText("?").assertIsDisplayed()
    }
    @Test fun failedImageStillShowsProfileInitial() = runComposeUiTest {
        val presenter = ProfilesPresenter(
            { ApiResult.Success(listOf(ProfileRow("fixture", "Nova", false, "file:///nonexistent-avatar-fixture.png"))) },
            { _, _ -> ApiResult.Success(null) }, { _, _ -> ProfileCommitResult.Committed })
        setContent { MaterialTheme { ProfilesScreen(presenter, {}, {}) } }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("Nova").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("N").assertIsDisplayed()
    }
}
