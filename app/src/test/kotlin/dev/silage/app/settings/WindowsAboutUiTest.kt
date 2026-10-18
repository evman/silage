package dev.silage.app.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import kotlin.test.Test
import org.koin.core.Koin

@OptIn(ExperimentalTestApi::class)
class WindowsAboutUiTest {
    @Test fun aboutRendersNeutralDescriptionAndPreservesAttribution() = runComposeUiTest {
        setContent { MaterialTheme { AboutSection(Koin()) } }
        onNodeWithText("Silage is a Silo client. Silo is a trademark of Silo Media L.L.C.; Silage is not affiliated with it.").assertIsDisplayed()
        onNodeWithText("client for Linux", substring = true).assertDoesNotExist()
    }
}
