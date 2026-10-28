package dev.silage.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AvatarInitialTest {
    @Test fun theInitialIsTheWholeFirstCharacter() {
        assertEquals("N", avatarInitial(" nova"))
        // An emoji is two UTF-16 halves; taking one showed as "?".
        assertEquals("🎮", avatarInitial("🎮Nova"))
        assertEquals("?", avatarInitial("  "))
    }

    @Test fun aShownAvatarIsKeptByAddress() {
        val image = ImageBitmap(2, 2)
        assertNull(AvatarCache.get("https://server/avatar-test.png"))
        AvatarCache.put("https://server/avatar-test.png", image)
        assertSame(image, AvatarCache.get("https://server/avatar-test.png"))
    }
}
