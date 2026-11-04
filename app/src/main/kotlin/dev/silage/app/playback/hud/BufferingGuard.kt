package dev.silage.app.playback.hud

/** A buttoned message after repeated buffering. Left alone, it goes away and nothing changes. */
data class QualityOffer(val message: String, val accept: String, val decline: String? = null)

/** How the player treats repeated buffering (Settings → Video). */
enum class BufferingMode(val wire: String, val label: String) {
    Auto("auto", "Automatically"), Ask("ask", "Ask first"), Off("off", "Never");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Auto }
}

/**
 * Notices a stream that keeps running out: [Stalls] stops of at least [MinStallMs]
 * within [WindowMs]. Stops just after a seek or at the start are normal and not counted.
 */
class BufferingGuard {
    private var stalledAt: Long? = null
    private var counted = false
    private var stalls = ArrayDeque<Long>()
    private var quietUntil = 0L

    /** A seek: the wait after it is expected. */
    fun seeked(now: Long) { quietUntil = maxOf(quietUntil, now + SeekGraceMs) }

    /** Called with each look at the player; true when it's time to do something about it. */
    fun update(buffering: Boolean, playing: Boolean, now: Long): Boolean {
        if (!playing) { stalledAt = null; return false }
        if (!buffering) { stalledAt = null; counted = false; return false }
        val since = stalledAt ?: now.also { stalledAt = it }
        if (counted || now - since < MinStallMs || since < quietUntil) return false
        counted = true
        stalls.addLast(now)
        while (stalls.isNotEmpty() && now - stalls.first() > WindowMs) stalls.removeFirst()
        if (stalls.size < Stalls) return false
        stalls.clear()
        quietUntil = now + CooldownMs
        return true
    }

    /** Started (or restarted) playing: the first fill of the buffer doesn't count. */
    fun started(now: Long) { quietUntil = now + StartGraceMs }

    companion object {
        const val Stalls = 3
        const val MinStallMs = 2_000L
        const val WindowMs = 120_000L
        const val SeekGraceMs = 5_000L
        const val StartGraceMs = 15_000L
        const val CooldownMs = 120_000L
    }
}

/** How long an offer stays on screen. */
const val OfferShownMs = 15_000L

/** One 4K playback's answer to repeated buffering: move to its 1080p copy, and whether to go without asking. */
class BufferingPlan(val automatic: Boolean, val switch: () -> Unit)

/** The subtitle showing when the player changes copy: a language to find again, or none at all. */
data class CarriedSubtitle(val language: String?, val off: Boolean = false)

/** What to carry to the other copy from these [tracks]; null when the choice can't be told apart by language. */
fun carriedSubtitle(tracks: List<Track>): CarriedSubtitle? {
    val showing = tracks.firstOrNull { it.selected } ?: return CarriedSubtitle(null, off = true)
    return showing.language?.takeIf { it.isNotBlank() }?.let { CarriedSubtitle(it) }
}

/** The `sid` to set on the new copy: "no", a track in the same language, or null to keep the plan's choice. */
fun carriedSubtitleId(carried: CarriedSubtitle?, tracks: List<Track>): String? = when {
    carried == null -> null
    carried.off -> "no"
    else -> tracks.firstOrNull { it.language.equals(carried.language, ignoreCase = true) }?.id?.toString()
}

/** The same language's track in the other copy, whose tracks may be in another order; null leaves the choice to the usual rules. */
fun sameLanguageAudio(from: List<org.siloserver.silo.model.catalog.AudioTrack>, ordinal: Int?, to: List<org.siloserver.silo.model.catalog.AudioTrack>): Int? {
    val language = from.getOrNull(ordinal ?: -1)?.language?.takeIf { it.isNotBlank() } ?: return null
    return to.indexOfFirst { it.language.equals(language, ignoreCase = true) }.takeIf { it >= 0 }
}
