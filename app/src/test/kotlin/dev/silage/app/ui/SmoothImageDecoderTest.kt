package dev.silage.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SmoothImageDecoderTest {
    @Test fun aPictureIsScaledToCoverOrFitItsBox() {
        // A 1000×1500 poster in a 300×450 card: a third either way.
        assertEquals(0.3, SmoothImageDecoder.decodeScale(1000, 1500, 300, 450, fill = true), 1e-9)
        // Cropped into a wider box it covers the width; fitted it stays inside the height.
        assertEquals(0.5, SmoothImageDecoder.decodeScale(1000, 1500, 500, 450, fill = true), 1e-9)
        assertEquals(0.3, SmoothImageDecoder.decodeScale(1000, 1500, 500, 450, fill = false), 1e-9)
        // A small picture is enlarged to its box (smoothly) rather than drawn blocky.
        assertEquals(2.0, SmoothImageDecoder.decodeScale(200, 300, 400, 600, fill = true), 1e-9)
        assertEquals(1.0, SmoothImageDecoder.decodeScale(200, 300, null, null, fill = true), 1e-9)
        // Never past 4096 on a side.
        assertEquals(4096 / 8000.0, SmoothImageDecoder.decodeScale(8000, 4000, 16000, 8000, fill = true), 1e-9)
    }

    @Test fun bigShrinksHalveFirstThenFinishExactly() {
        assertEquals(listOf(1000 to 1500, 500 to 750, 333 to 500), SmoothImageDecoder.steps(2000, 3000, 333, 500))
        assertEquals(listOf(400 to 600), SmoothImageDecoder.steps(500, 750, 400, 600))
        assertEquals(listOf(600 to 900), SmoothImageDecoder.steps(300, 450, 600, 900))
        assertEquals(emptyList(), SmoothImageDecoder.steps(300, 450, 300, 450))
        assertEquals(listOf(250 to 375), SmoothImageDecoder.steps(500, 750, 250, 375))
    }

    @Test fun resizingRealPixelsGivesTheExactSize() {
        val src = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(640, 960); erase(0xFF336699.toInt()) }
        src.setImmutable()
        val image = org.jetbrains.skia.Image.makeFromBitmap(src)
        try {
            val out = SmoothImageDecoder.resize(image, 150, 225)
            assertEquals(150, out.width); assertEquals(225, out.height)
            // A flat colour stays that colour through the halvings and the cubic step.
            val c = out.getColor(75, 112)
            listOf(16 to 0x33, 8 to 0x66, 0 to 0x99).forEach { (shift, want) ->
                kotlin.test.assertTrue(kotlin.math.abs(((c shr shift) and 0xFF) - want) <= 2, "colour drifted: ${Integer.toHexString(c)}")
            }
            out.close()
        } finally { image.close(); src.close() }
    }
}
