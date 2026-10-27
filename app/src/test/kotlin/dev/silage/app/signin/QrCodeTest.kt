package dev.silage.app.signin

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrCodeTest {
    private val url = "https://silo.example.com/activate?code=48217730"

    @Test fun matrixIsSquareWithAFourModuleQuietZone() {
        val qr = QrMatrix.encode(url)
        assertTrue(qr.size >= 21 + 8, "at least a version 1 symbol plus its quiet zone: ${qr.size}")
        for (i in 0 until qr.size) for (q in 0 until QrMatrix.QUIET_ZONE) {
            assertFalse(qr[i, q] || qr[q, i] || qr[i, qr.size - 1 - q] || qr[qr.size - 1 - q, i], "quiet zone at ($i,$q)")
        }
        // The top-left finder pattern starts right after the quiet zone.
        assertTrue(qr[QrMatrix.QUIET_ZONE, QrMatrix.QUIET_ZONE])
    }

    @Test fun matrixDecodesBackToTheUrl() {
        val qr = QrMatrix.encode(url)
        val scale = 4
        val px = qr.size * scale
        // Dark modules on a white background, the way the panel draws it.
        val pixels = IntArray(px * px) { i ->
            if (qr[(i % px) / scale, (i / px) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(px, px, pixels)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }
}
