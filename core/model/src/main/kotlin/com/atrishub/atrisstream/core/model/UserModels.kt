package com.atrishub.atrisstream.core.model

import kotlinx.serialization.Serializable

/**
 * Tracks playback position for resume-watching functionality.
 */
@Serializable
data class WatchProgress(
    val mediaId: String,
    val sourceId: String,
    val type: MediaType,
    val title: String,
    val posterUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
    val isCompleted: Boolean = false
)

/**
 * Policy constants and completion heuristics for watch progress and Continue Watching.
 */
object WatchProgressPolicy {
    const val MIN_RESUME_POSITION_MS: Long = 10_000L

    fun isPlaybackCompleted(
        positionMs: Long,
        durationMs: Long,
        isEnded: Boolean = false
    ): Boolean {
        if (isEnded) return true
        if (durationMs <= 0L || positionMs <= 0L) return false
        val fraction = positionMs.toFloat() / durationMs.toFloat()
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        return when {
            durationMs < 30 * 60_000L ->
                fraction >= 0.88f || (remainingMs <= 150_000L && fraction >= 0.80f)
            durationMs < 75 * 60_000L ->
                fraction >= 0.90f || (remainingMs <= 600_000L && fraction >= 0.80f)
            else ->
                fraction >= 0.92f || (remainingMs <= 720_000L && fraction >= 0.85f)
        }
    }
}

/**
 * Represents a media item saved in the user's watchlist.
 */
@Serializable
data class WatchlistItem(
    val mediaId: String,
    val sourceId: String,
    val type: MediaType,
    val title: String,
    val posterUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val addedAt: Long = 0L
)

/**
 * User-configurable subtitle rendering preferences.
 */
@Serializable
data class SubtitleAppearance(
    val fontSizeSp: Float = 20f,
    val textColorArgb: Long = 0xFFFFFFFFL,
    val backgroundColorArgb: Long = 0x80000000L,
    val edgeType: Int = 1
)

/**
 * Navigation payload required to launch the video player for a title or episode.
 */
@Serializable
data class PlayerLaunchArgs(
    val sourceId: String,
    val mediaId: String,
    val type: MediaType,
    val title: String,
    val posterUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val resumePositionMs: Long = 0L
)
