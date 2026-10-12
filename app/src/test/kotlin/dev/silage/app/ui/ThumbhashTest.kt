package dev.silage.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThumbhashTest {
    // The reference hash from evanw/thumbhash's README (an opaque landscape image).
    @Test fun decodesTheReferenceHash() {
        val img = Thumbhash.decode("1QcSHQRnh493V4dIh4eXh1h4kJUI")!!
        assertEquals(img.width * img.height, img.argb.size)
        assertTrue(img.width in 20..32 && img.height in 20..32, "${img.width}x${img.height}")
        assertTrue(img.argb.all { it ushr 24 == 0xFF }, "opaque image decoded with transparency")
    }

    @Test fun garbageIsNull() {
        assertNull(Thumbhash.decode("!!"))
        assertNull(Thumbhash.decode(""))
    }
}
