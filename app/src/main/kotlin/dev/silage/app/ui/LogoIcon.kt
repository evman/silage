package dev.silage.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.painter.Painter

/** The app icon: the white logo on a near-black rounded square. */
object LogoIcon : Painter() {
    override val intrinsicSize = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val d = size.minDimension
        drawRoundRect(Color(0xFF0B0C0F), cornerRadius = CornerRadius(d * 0.22f))
        inset(d * 0.17f) { drawPath(SilageLogo.path(size), Color.White) }
    }
}
