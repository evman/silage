package dev.silage.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.hypot

/**
 * Silage's mark: a teardrop with a rounded play triangle cut out. Traced
 * from the chosen design in a 1440-unit square (the logo spans x 392–1046,
 * y 260–1148): the drop's sides are cubic curves fitted to the design's
 * bulge (within 2 units), its bottom a circle of radius 327.
 */
object SilageLogo {
    /** The logo's own bounds within the 1440 square, for centering. */
    val bounds = Rect(392f, 260f, 1046f, 1148f)

    /** A triangle with straight sides and circular corners of [radius]. */
    private fun roundedTriangle(a: Offset, b: Offset, c: Offset, radius: Float): Path {
        val v = listOf(a, b, c)
        fun unit(from: Offset, to: Offset): Offset {
            val len = hypot(to.x - from.x, to.y - from.y)
            return Offset((to.x - from.x) / len, (to.y - from.y) / len)
        }
        return Path().apply {
            for (i in 0..2) {
                val cur = v[i]
                val toPrev = unit(cur, v[(i + 2) % 3])
                val toNext = unit(cur, v[(i + 1) % 3])
                val cos = (toPrev.x * toNext.x + toPrev.y * toNext.y).coerceIn(-1f, 1f)
                val half = kotlin.math.acos(cos) / 2
                val along = radius / kotlin.math.tan(half)            // vertex → tangent point
                val bis = unit(Offset.Zero, toPrev + toNext)
                val center = cur + bis * (radius / kotlin.math.sin(half))
                val p1 = cur + toPrev * along
                val p2 = cur + toNext * along
                if (i == 0) moveTo(p1.x, p1.y) else lineTo(p1.x, p1.y)
                val start = Math.toDegrees(kotlin.math.atan2((p1.y - center.y).toDouble(), (p1.x - center.x).toDouble())).toFloat()
                var sweep = Math.toDegrees(kotlin.math.atan2((p2.y - center.y).toDouble(), (p2.x - center.x).toDouble())).toFloat() - start
                if (sweep > 180f) sweep -= 360f
                if (sweep < -180f) sweep += 360f
                arcTo(Rect(center, radius), start, sweep, false)
            }
            close()
        }
    }

    /** The logo path scaled so its bounds fit [size] (centered, aspect kept). */
    fun path(size: Size): Path {
        val drop = Path().apply {
            moveTo(719f, 260f)
            cubicTo(669f, 300f, 392f, 581f, 392f, 821f)
            arcTo(Rect(Offset(719f, 821f), 327f), 180f, -180f, false)
            cubicTo(1046f, 581f, 769f, 300f, 719f, 260f)
            close()
        }
        val play = playTriangle()
        // The triangle lies wholly inside the drop, so an even-odd fill cuts it out.
        val logo = Path().apply { fillType = PathFillType.EvenOdd; addPath(drop); addPath(play) }
        return fit(logo, size, evenOdd = true)
    }

    /** Just the play triangle, placed as in [path] (to keep the cutout dark under a glow). */
    fun playPath(size: Size): Path = fit(playTriangle(), size, evenOdd = false)

    private fun fit(p: Path, size: Size, evenOdd: Boolean): Path {
        val scale = minOf(size.width / bounds.width, size.height / bounds.height)
        val dx = (size.width - bounds.width * scale) / 2 - bounds.left * scale
        val dy = (size.height - bounds.height * scale) / 2 - bounds.top * scale
        return Path().apply {
            if (evenOdd) fillType = PathFillType.EvenOdd
            addPath(p)
            transform(androidx.compose.ui.graphics.Matrix().apply { translate(dx, dy); scale(scale, scale) })
        }
    }

    private fun playTriangle() = roundedTriangle(Offset(572f, 525f), Offset(572f, 973f), Offset(950f, 749f), radius = 60f)
}
