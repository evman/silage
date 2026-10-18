package dev.silage.app.playback

/** Session-only display adjustments; never changes the decoded source or HDR output mode. */
enum class VideoFit(val label: String) { Fit("Fit (letterbox)"), Fill("Fill (crop edges)") }
enum class VideoAspect(val label: String, val property: String) {
    Original("Original", "no"), SixteenNine("16:9", "16:9"), FourThree("4:3", "4:3"), Cinema("2.35:1", "2.35:1")
}
data class VideoSettings(
    val fit: VideoFit = VideoFit.Fit,
    val aspect: VideoAspect = VideoAspect.Original,
    val zoom: Double = 0.0,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val gamma: Int = 0,
) {
    fun bounded() = copy(zoom = if (zoom.isFinite()) zoom.coerceIn(-1.0, 1.0) else 0.0,
        brightness = brightness.coerceIn(-100, 100), contrast = contrast.coerceIn(-100, 100),
        saturation = saturation.coerceIn(-100, 100), gamma = gamma.coerceIn(-100, 100))
    fun properties(): Map<String, String> = bounded().let { s -> linkedMapOf(
        "keepaspect" to "yes", "panscan" to if (s.fit == VideoFit.Fill) "1" else "0",
        "video-aspect-override" to s.aspect.property, "video-zoom" to s.zoom.toString(),
        "brightness" to s.brightness.toString(), "contrast" to s.contrast.toString(),
        "saturation" to s.saturation.toString(), "gamma" to s.gamma.toString()) }
}

object VideoControls {
    val labels = listOf("Fit", "Aspect ratio", "Zoom", "Brightness", "Contrast", "Saturation", "Gamma")
    fun adjust(s: VideoSettings, index: Int, step: Int): VideoSettings = when (index) {
        0 -> s.copy(fit = VideoFit.entries[Math.floorMod(s.fit.ordinal + step, VideoFit.entries.size)])
        1 -> s.copy(aspect = VideoAspect.entries[Math.floorMod(s.aspect.ordinal + step, VideoAspect.entries.size)])
        2 -> s.copy(zoom = kotlin.math.round((s.zoom + step * 0.1) * 10) / 10)
        3 -> s.copy(brightness = s.brightness + step * 5)
        4 -> s.copy(contrast = s.contrast + step * 5)
        5 -> s.copy(saturation = s.saturation + step * 5)
        6 -> s.copy(gamma = s.gamma + step * 5)
        7 -> VideoSettings()
        else -> s
    }.bounded()
    fun values(s: VideoSettings) = listOf(s.fit.label, s.aspect.label,
        "%.0f%%".format(java.util.Locale.ROOT, Math.pow(2.0, s.zoom) * 100),
        s.brightness.toString(), s.contrast.toString(), s.saturation.toString(), s.gamma.toString())
}

/** Shared across audio/stream replans; commit displayed values only after native acceptance. */
class VideoSession {
    @Volatile var settings = VideoSettings(); private set
    @Volatile var error: String? = null; private set
    @Synchronized fun update(next: VideoSettings, set: (String, String) -> Int, get: (String) -> String?): Boolean {
        val values = next.bounded().properties()
        val failed = values.keys.filter { set(it, values.getValue(it)) < 0 || !matches(it, values.getValue(it), get(it)) }
        if (failed.isNotEmpty()) {
            settings.properties().forEach { (k,v) -> set(k,v) }
            error = "Video adjustment unavailable: ${failed.joinToString()}"
            return false
        }
        settings = next.bounded(); error = null
        return true
    }
    fun apply(set: (String, String) -> Int, get: (String) -> String?) = update(settings, set, get)

    private fun matches(key: String, requested: String, actual: String?): Boolean {
        if (actual == null) return false
        if (actual == requested) return true
        if (key == "video-aspect-override" && requested == "no") return actual.toDoubleOrNull() == -2.0
        fun number(value: String): Double? = if (':' in value) {
            val parts = value.split(':')
            if (parts.size == 2) parts[0].toDoubleOrNull()?.let { a -> parts[1].toDoubleOrNull()?.takeIf { it != 0.0 }?.let { a / it } } else null
        } else value.toDoubleOrNull()
        val expected = number(requested) ?: return false
        val readback = number(actual) ?: return false
        return kotlin.math.abs(expected - readback) < 0.00001
    }
}
