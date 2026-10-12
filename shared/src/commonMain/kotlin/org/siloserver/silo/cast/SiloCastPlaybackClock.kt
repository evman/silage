package org.siloserver.silo.cast

import kotlin.math.abs

class SiloCastPlaybackClock(
    private val optimisticWindowMs: Long = 2_000L,
) {
    private var snapshot: SiloCastPlaybackState? = null
    private var snapshotAtMs: Long = 0L
    private var optimisticTime: OptimisticTime? = null
    private var optimisticPlaying: OptimisticPlaying? = null

    fun ingest(state: SiloCastPlaybackState, nowMs: Long) {
        snapshot = state
        snapshotAtMs = nowMs

        optimisticTime = optimisticTime?.takeUnless { optimistic ->
            optimistic.isExpired(nowMs, optimisticWindowMs) || abs(state.currentTime - optimistic.seconds) <= SEEK_CATCH_UP_SECONDS
        }
        optimisticPlaying = optimisticPlaying?.takeUnless { optimistic ->
            optimistic.isExpired(nowMs, optimisticWindowMs) || state.isPlaying == optimistic.isPlaying
        }
    }

    fun displayTime(nowMs: Long): Double {
        val state = snapshot ?: return 0.0
        val optimistic = optimisticTime
        if (optimistic != null && !optimistic.isExpired(nowMs, optimisticWindowMs)) {
            return optimistic.seconds.clampToDuration(state.duration)
        }

        val elapsedSeconds = if (isPlaying(nowMs)) {
            ((nowMs - snapshotAtMs).coerceAtLeast(0L) / 1_000.0) * state.playbackSpeed.coerceAtLeast(0.0)
        } else {
            0.0
        }
        return (state.currentTime + elapsedSeconds).clampToDuration(state.duration)
    }

    fun isPlaying(nowMs: Long): Boolean {
        val optimistic = optimisticPlaying
        if (optimistic != null && !optimistic.isExpired(nowMs, optimisticWindowMs)) {
            return optimistic.isPlaying
        }
        return snapshot?.isPlaying ?: false
    }

    fun setOptimisticTime(seconds: Double, nowMs: Long) {
        optimisticTime = OptimisticTime(seconds = seconds, updatedAtMs = nowMs)
    }

    fun setOptimisticPlaying(isPlaying: Boolean, nowMs: Long) {
        optimisticPlaying = OptimisticPlaying(isPlaying = isPlaying, updatedAtMs = nowMs)
    }

    private fun Double.clampToDuration(duration: Double): Double {
        val lower = coerceAtLeast(0.0)
        return if (duration > 0.0) lower.coerceAtMost(duration) else lower
    }

    private data class OptimisticTime(
        val seconds: Double,
        val updatedAtMs: Long,
    ) {
        fun isExpired(nowMs: Long, windowMs: Long): Boolean = nowMs - updatedAtMs > windowMs
    }

    private data class OptimisticPlaying(
        val isPlaying: Boolean,
        val updatedAtMs: Long,
    ) {
        fun isExpired(nowMs: Long, windowMs: Long): Boolean = nowMs - updatedAtMs > windowMs
    }

    private companion object {
        const val SEEK_CATCH_UP_SECONDS = 0.5
    }
}
