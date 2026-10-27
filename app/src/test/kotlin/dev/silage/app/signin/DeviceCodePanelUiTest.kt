package dev.silage.app.signin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeviceCodePanelUiTest {
    private val code = DeviceCode("4821 7730", "https://silo.test/activate?code=48217730", "silo.test/activate")

    @Test fun showsTheCodeTheAddressAndTheQr() = runComposeUiTest {
        setContent { MaterialTheme { DeviceCodePanel(DeviceSignInUi(code, DeviceSignInStatus.Opened), "silo.test", onShowNewCode = {}) } }
        onNodeWithText("4821 7730").assertIsDisplayed()
        onNodeWithText("Go to silo.test/activate", substring = true).assertIsDisplayed()
        onNodeWithText("Continue on your phone").assertIsDisplayed()
        onNodeWithContentDescription("Sign-in QR code", substring = true).assertIsDisplayed()
    }

    @Test fun pausedOffersANewCode() = runComposeUiTest {
        var asked = 0
        setContent { MaterialTheme { DeviceCodePanel(DeviceSignInUi(null, DeviceSignInStatus.Paused), "silo.test", onShowNewCode = { asked++ }) } }
        onNodeWithText("Sign-in paused", substring = true).assertIsDisplayed()
        onNodeWithText("Show a new code").performClick()
        assertEquals(1, asked)
    }

    @Test fun unreachableNamesTheServer() = runComposeUiTest {
        setContent { MaterialTheme { DeviceCodePanel(DeviceSignInUi(code, DeviceSignInStatus.Unreachable), "silo.test", onShowNewCode = {}) } }
        onNodeWithText("Can't reach silo.test", substring = true).assertIsDisplayed()
    }
}
