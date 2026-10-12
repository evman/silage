package org.siloserver.silo.model.playback

/**
 * Resolves the optional position sent to playback/start. A non-null override
 * is an explicit command, so zero is valid there; stored detail progress only
 * counts when it represents a real resume point.
 */
/**
 * An explicit resume override of exactly 0.0 is "Start Over" (Apple's player
 * route models this as `startFromBeginning`; Android encodes it as a 0.0
 * override). It must behave like every other explicit override: no resume
 * rewind, and honored verbatim after the server answers — otherwise a
 * zero session position gets discarded and stale detail progress resumes.
 */
fun isExplicitStartOver(overridePosition: Double?): Boolean = overridePosition == 0.0

fun resolvePlaybackStartRequestPosition(
    overridePosition: Double?,
    detailPosition: Double?,
): Double? =
    overridePosition?.takeIf { it.isFinite() && it >= 0.0 }
        ?: detailPosition?.takeIf { it.isFinite() && it > 0.0 }

/**
 * Resolves the actual player start position after the server answers. Prefer
 * the server/session position over detail progress so stale zero values from
 * the detail payload cannot erase a real resume point.
 */
fun resolvePlaybackStartPosition(
    overridePosition: Double?,
    sessionPosition: Double,
    detailPosition: Double?,
): Double =
    overridePosition?.takeIf { it.isFinite() && it >= 0.0 }
        ?: sessionPosition.takeIf { it.isFinite() && it > 0.0 }
        ?: detailPosition?.takeIf { it.isFinite() && it > 0.0 }
        ?: 0.0

/** Below this resume point we do not nudge backwards (too close to the start to matter). */
const val MinResumeForRewindSeconds: Double = 30.0

/**
 * Default skip-back-on-resume amount. A per-device setting can override this
 * later (R4: the `player.resume_rewind_seconds` key is not server-registered
 * yet and there is no UI, so the value is fixed for now).
 */
const val DefaultResumeRewindSeconds: Double = 7.0

/**
 * Skip-back-on-resume: when resuming a partially-watched item, begin a few
 * seconds before the saved position to re-establish context.
 *
 * Applies ONLY to a genuine resume — never to a fresh start (resolved position
 * 0) and never to an explicit override (Start Over / a commanded position /
 * Watch Together anchor / retry, all flagged via [isExplicitOverride]). Resumes
 * below [MinResumeForRewindSeconds] are left untouched. The result is clamped
 * at 0.
 */
fun applyResumeRewind(
    resolvedStartPosition: Double,
    isExplicitOverride: Boolean,
    rewindSeconds: Double,
): Double {
    // Defensive: a non-finite/negative start is treated as a fresh start, and a
    // non-finite/non-positive rewind disables the nudge — so a bad setting or a
    // future direct caller can never produce an invalid seek position.
    val start = resolvedStartPosition.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    if (isExplicitOverride) return start
    val rewind = rewindSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return start
    if (start < MinResumeForRewindSeconds) return start
    return (start - rewind).coerceAtLeast(0.0)
}
