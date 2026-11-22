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
    val updatedAt: Long = 0L
)

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
