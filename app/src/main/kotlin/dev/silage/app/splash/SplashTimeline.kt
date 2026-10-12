package dev.silage.app.splash

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/** One frame of the startup animation. */
data class SplashFrame(
    val logoAlpha: Float,
    /** 0 = soft grey, 1 = crisp white. */
    val brightness: Float,
    val scale: Float,
    /** Strength of the amber glow behind the logo. */
    val amber: Float,
    /** Opacity of the whole splash; it fades out over the app. */
    val screenAlpha: Float,
    val finished: Boolean,
)

/**
 * The startup animation, after the Grok concept: the logo fades in soft and
 * grey, brightens to white, an amber glow breathes behind it while the app
 * loads, then the splash fades away — never before the intro has played.
 */
class SplashTimeline(private val introMs: Long = 1600, private val fadeMs: Long = 450, private val pulseMs: Long = 2400) {
    private fun ease(x: Float) = (1 - cos(PI * x.coerceIn(0f, 1f)).toFloat()) / 2

    fun frame(elapsedMs: Long, readyAtMs: Long?): SplashFrame {
        val intro = (elapsedMs.toFloat() / introMs).coerceIn(0f, 1f)
        val fadeIn = ease(intro / 0.55f)
        val brighten = ease((intro - 0.45f) / 0.55f)
        // Glow builds in the second half of the intro, then breathes.
        val breath = if (elapsedMs < introMs) 0.6f * ease((intro - 0.5f) / 0.5f)
            else 0.35f + 0.45f * (0.5f - 0.5f * cos(2 * PI * ((elapsedMs - introMs).toDouble() / pulseMs)).toFloat())
        val outStart = readyAtMs?.let { maxOf(it, introMs) }
        val out = if (outStart == null) 0f else ((elapsedMs - outStart).toFloat() / fadeMs).coerceIn(0f, 1f)
        return SplashFrame(
            logoAlpha = fadeIn,
            brightness = 0.72f + 0.28f * brighten,
            scale = 0.94f + 0.06f * ease(intro),
            amber = min(1f, breath) * (1 - out),
            screenAlpha = 1 - ease(out),
            finished = outStart != null && elapsedMs >= outStart + fadeMs,
        )
    }
}
