package dev.silage.app.playback

import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.model.settings.pointSize
import kotlin.math.roundToInt

/** Subtitle text colors offered in Settings, as mpv `#RRGGBB`. */
enum class SubtitleColor(val wire: String, val label: String, val rgb: String) {
    White("white", "White", "#FFFFFF"),
    Yellow("yellow", "Yellow", "#FFE14D"),
    Cyan("cyan", "Cyan", "#7FE7FF"),
    Green("green", "Green", "#9CF07A"),
    ;

    companion object {
        fun of(wire: String?): SubtitleColor = entries.firstOrNull { it.wire == wire } ?: White
    }
}

/**
 * How subtitles look on this PC: Silo's size, background and position presets
 * plus a text color. Stored by wire name in `ui.json`; unknown values fall back
 * to the defaults.
 */
data class SubtitleStyle(
    val size: SubtitleFontSizePreset = SubtitleFontSizePreset.Large,
    val background: SubtitleBackgroundStylePreset = SubtitleBackgroundStylePreset.Shadow,
    val position: SubtitlePositionPreset = SubtitlePositionPreset.Bottom,
    val color: SubtitleColor = SubtitleColor.White,
) {
    /**
     * The mpv options for this style. Silo's point sizes are for a 1080p
     * frame and mpv's `sub-font-size` is in pixels at a 720p window, so they
     * are scaled by 0.72 (Large lands on mpv's own default of about 40).
     * Styled (ASS) subtitles keep their own look apart from the size.
     */
    fun mpvOptions(): Map<String, String> {
        val options = linkedMapOf(
            "sub-font-size" to (size.pointSize * 0.72).roundToInt().toString(),
            "sub-color" to color.rgb,
        )
        when (background) {
            SubtitleBackgroundStylePreset.Box -> options += mapOf(
                "sub-border-style" to "background-box", "sub-back-color" to "#BF000000",
                "sub-border-size" to "0", "sub-shadow-offset" to "0")
            SubtitleBackgroundStylePreset.Shadow -> options += mapOf(
                "sub-border-style" to "outline-and-shadow", "sub-border-size" to "1", "sub-border-color" to "#80000000",
                "sub-shadow-offset" to "3", "sub-shadow-color" to "#C0000000")
            SubtitleBackgroundStylePreset.Outline -> options += mapOf(
                "sub-border-style" to "outline-and-shadow", "sub-border-size" to "3", "sub-border-color" to "#FF000000",
                "sub-shadow-offset" to "0")
            SubtitleBackgroundStylePreset.None -> options += mapOf(
                "sub-border-style" to "outline-and-shadow", "sub-border-size" to "0", "sub-shadow-offset" to "0")
        }
        when (position) {
            SubtitlePositionPreset.Bottom -> options += mapOf("sub-align-y" to "bottom", "sub-pos" to "100")
            SubtitlePositionPreset.LowerThird -> options += mapOf("sub-align-y" to "bottom", "sub-pos" to "82")
            SubtitlePositionPreset.Top -> options += mapOf("sub-align-y" to "top", "sub-pos" to "100")
        }
        return options
    }

    companion object {
        fun of(size: String?, background: String?, position: String?, color: String?) = SubtitleStyle(
            size = SubtitleFontSizePreset.entries.firstOrNull { it.wire == size } ?: SubtitleFontSizePreset.Large,
            background = SubtitleBackgroundStylePreset.entries.firstOrNull { it.wire == background } ?: SubtitleBackgroundStylePreset.Shadow,
            position = SubtitlePositionPreset.entries.firstOrNull { it.wire == position } ?: SubtitlePositionPreset.Bottom,
            color = SubtitleColor.of(color),
        )

        val sizeLabels = mapOf(
            SubtitleFontSizePreset.Small to "Small", SubtitleFontSizePreset.Medium to "Medium", SubtitleFontSizePreset.Large to "Large",
            SubtitleFontSizePreset.XLarge to "Larger", SubtitleFontSizePreset.XXLarge to "Largest",
        )
        val backgroundLabels = mapOf(
            SubtitleBackgroundStylePreset.Shadow to "Shadow", SubtitleBackgroundStylePreset.Outline to "Outline",
            SubtitleBackgroundStylePreset.Box to "Dark box", SubtitleBackgroundStylePreset.None to "None",
        )
        val positionLabels = mapOf(
            SubtitlePositionPreset.Bottom to "Bottom", SubtitlePositionPreset.LowerThird to "Raised", SubtitlePositionPreset.Top to "Top",
        )
    }
}

/** The rows of the player's Subtitle look menu. */
object SubtitleLookRows {
    const val Count = 5
    private const val ResetIndex = 4

    private fun <T> List<T>.after(current: T, step: Int): T = this[(indexOf(current) + step).mod(size)]

    /** [style] after moving row [index] by [step]; the last row puts everything back. */
    fun stepped(style: SubtitleStyle, index: Int, step: Int): SubtitleStyle = when (index) {
        0 -> style.copy(size = SubtitleStyle.sizeLabels.keys.toList().after(style.size, step))
        1 -> style.copy(background = SubtitleStyle.backgroundLabels.keys.toList().after(style.background, step))
        2 -> style.copy(color = SubtitleColor.entries.after(style.color, step))
        3 -> style.copy(position = SubtitleStyle.positionLabels.keys.toList().after(style.position, step))
        ResetIndex -> SubtitleStyle()
        else -> style
    }

    fun labels(style: SubtitleStyle): List<String> = listOf(
        "Size · ${SubtitleStyle.sizeLabels[style.size]}",
        "Background · ${SubtitleStyle.backgroundLabels[style.background]}",
        "Color · ${style.color.label}",
        "Position · ${SubtitleStyle.positionLabels[style.position]}",
        "Reset to default",
    )
}
