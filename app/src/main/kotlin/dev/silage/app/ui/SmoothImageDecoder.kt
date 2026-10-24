package dev.silage.app.ui

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes artwork and resizes it to the size it is drawn at, smoothly. Coil's own desktop decoder
 * resizes in one step with Skia's default nearest-pixel sampling, which leaves a large poster
 * shrunk to card size jagged and over-sharp, and a small one blocky when enlarged. Here a picture
 * is halved (each step averaging 2×2 pixels) while it is at least twice too big, then brought to
 * the exact size with a cubic (Mitchell) filter, so it is drawn on screen pixel for pixel.
 */
class SmoothImageDecoder(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val image = Image.makeFromEncoded(bytes)
        try {
            val factor = decodeScale(image.width, image.height, options.size.width.pixels(), options.size.height.pixels(),
                fill = options.scale == Scale.FILL)
            val outWidth = max(1, (image.width * factor).roundToInt())
            val outHeight = max(1, (image.height * factor).roundToInt())
            val bitmap = resize(image, outWidth, outHeight)
            bitmap.setImmutable()
            return DecodeResult(image = bitmap.asImage(), isSampled = outWidth < image.width || outHeight < image.height)
        } finally {
            image.close()
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder =
            SmoothImageDecoder(result.source, options)
    }

    companion object {
        private fun Dimension.pixels(): Int? = (this as? Dimension.Pixels)?.px?.takeIf { it > 0 }

        /** Largest side a decoded picture may have, as in Coil. */
        internal const val MaxSide = 4096

        /**
         * How much to scale a [srcWidth]×[srcHeight] picture for a [width]×[height] box (null: any):
         * to cover the box with [fill] (cropped), else to fit inside it. Never past [MaxSide].
         */
        internal fun decodeScale(srcWidth: Int, srcHeight: Int, width: Int?, height: Int?, fill: Boolean): Double {
            val byWidth = width?.let { it / srcWidth.toDouble() }
            val byHeight = height?.let { it / srcHeight.toDouble() }
            val factor = when {
                byWidth != null && byHeight != null -> if (fill) max(byWidth, byHeight) else min(byWidth, byHeight)
                byWidth != null -> byWidth
                byHeight != null -> byHeight
                else -> 1.0
            }
            return min(factor, MaxSide / maxOf(srcWidth, srcHeight, 1).toDouble())
        }

        /** The sizes to resize through: halvings while still at least twice too big, then the exact size. */
        internal fun steps(srcWidth: Int, srcHeight: Int, outWidth: Int, outHeight: Int): List<Pair<Int, Int>> = buildList {
            var w = srcWidth
            var h = srcHeight
            while (w / 2 >= outWidth && h / 2 >= outHeight && w / 2 > 0 && h / 2 > 0) {
                w /= 2; h /= 2
                add(w to h)
            }
            if (w != outWidth || h != outHeight) add(outWidth to outHeight)
        }

        private fun draw(image: Image, srcWidth: Int, srcHeight: Int, width: Int, height: Int, sampling: SamplingMode): Bitmap {
            val bitmap = Bitmap()
            bitmap.allocN32Pixels(width, height)
            val canvas = Canvas(bitmap)
            try {
                canvas.drawImageRect(image, Rect.makeWH(srcWidth.toFloat(), srcHeight.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()),
                    sampling, null, true)
            } finally {
                canvas.close()
            }
            return bitmap
        }

        internal fun resize(image: Image, outWidth: Int, outHeight: Int): Bitmap {
            val plan = steps(image.width, image.height, outWidth, outHeight)
            if (plan.isEmpty()) return draw(image, image.width, image.height, image.width, image.height, SamplingMode.DEFAULT)
            var current = image
            var currentWidth = image.width
            var currentHeight = image.height
            var intermediate: Image? = null
            try {
                plan.forEachIndexed { i, (w, h) ->
                    val halving = w * 2 <= currentWidth + 1 && h * 2 <= currentHeight + 1 && w * 2 >= currentWidth - 1
                    // At exactly half, linear sampling averages each 2×2 block; anything else is cubic.
                    val bitmap = draw(current, currentWidth, currentHeight, w, h, if (halving) SamplingMode.LINEAR else SamplingMode.MITCHELL)
                    if (i == plan.lastIndex) return bitmap
                    bitmap.setImmutable()
                    val next = Image.makeFromBitmap(bitmap)
                    bitmap.close()
                    intermediate?.close()
                    intermediate = next
                    current = next
                    currentWidth = w
                    currentHeight = h
                }
            } finally {
                intermediate?.close()
            }
            error("unreachable")
        }
    }
}
