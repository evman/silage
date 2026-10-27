package dev.silage.app.signin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR symbol as modules, [QUIET_ZONE] light modules on every side included.
 * Error correction M, as the Android TV app uses: enough to survive a phone
 * camera at an angle without inflating the module count for short URLs.
 */
class QrMatrix private constructor(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        const val QUIET_ZONE = 4

        fun encode(content: String): QrMatrix {
            val code = Encoder.encode(content, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
            val size = code.width + 2 * QUIET_ZONE
            val dark = BooleanArray(size * size)
            for (y in 0 until code.height) for (x in 0 until code.width) {
                if (code.get(x, y).toInt() == 1) dark[(y + QUIET_ZONE) * size + x + QUIET_ZONE] = true
            }
            return QrMatrix(size, dark)
        }
    }
}

/** [content] as a QR code: black modules on white, quiet zone included, so phones can scan it off a dark screen. */
@Composable
fun QrCode(content: String, size: Dp, description: String, modifier: Modifier = Modifier) {
    val qr = remember(content) { QrMatrix.encode(content) }
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        drawRect(Color.White)
        // Whole pixels per module keep the edges crisp; the remainder centres the symbol.
        val module = kotlin.math.floor(this.size.minDimension / qr.size).coerceAtLeast(1f)
        val origin = (this.size.minDimension - module * qr.size) / 2
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) drawRect(Color.Black, Offset(origin + x * module, origin + y * module), Size(module, module))
        }
    }
}
