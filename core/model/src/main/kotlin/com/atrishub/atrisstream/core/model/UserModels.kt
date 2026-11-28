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
    val addedAt: Long = 0L,
    val lastWatchedSeason: Int? = null,
    val lastWatchedEpisode: Int? = null,
    val knownEpisodeCount: Int = 0,
    val latestAvailableSeason: Int? = null,
    val latestAvailableEpisode: Int? = null,
    val latestEpisodeCount: Int = 0,
    val hasNewEpisode: Boolean = false,
    val lastCheckedAt: Long = 0L
)

/**
 * Policy for tracking newly released episodes on saved Watchlist items.
 */
object WatchlistTrackingPolicy {

    fun evaluateWatchlistUpdate(
        item: WatchlistItem,
        releasedEpisodes: List<Episode>,
        progressList: List<WatchProgress>,
        nowMs: Long
    ): WatchlistItem {
        val released = releasedEpisodes.filter { it.isReleased }
        val releasedCount = released.size
        val latestEp = released.maxWithOrNull(
            compareBy<Episode>({ it.seasonNumber }, { it.episodeNumber })
        )

        val completedProgress = progressList.filter {
            it.isCompleted && it.seasonNumber != null && it.episodeNumber != null
        }
        val highestCompleted = completedProgress.maxWithOrNull(
            compareBy<WatchProgress>({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 })
        )

        val lastWatchedSeason = highestCompleted?.seasonNumber
        val lastWatchedEpisode = highestCompleted?.episodeNumber

        if (releasedCount == 0) {
            return item.copy(
                lastWatchedSeason = lastWatchedSeason,
                lastWatchedEpisode = lastWatchedEpisode,
                lastCheckedAt = nowMs
            )
        }

        val latestSeason = latestEp?.seasonNumber ?: item.latestAvailableSeason
        val latestEpisodeNum = latestEp?.episodeNumber ?: item.latestAvailableEpisode

        if (item.knownEpisodeCount == 0) {
            return item.copy(
                lastWatchedSeason = lastWatchedSeason,
                lastWatchedEpisode = lastWatchedEpisode,
                knownEpisodeCount = releasedCount,
                latestAvailableSeason = latestSeason,
                latestAvailableEpisode = latestEpisodeNum,
                latestEpisodeCount = releasedCount,
                hasNewEpisode = false,
                lastCheckedAt = nowMs
            )
        }

        val isLatestCompleted = latestSeason != null && latestEpisodeNum != null && (
            completedProgress.any {
                it.seasonNumber == latestSeason && it.episodeNumber == latestEpisodeNum
            } || (
                lastWatchedSeason != null && lastWatchedEpisode != null &&
                    compareEpisodeOrder(lastWatchedSeason, lastWatchedEpisode, latestSeason, latestEpisodeNum) >= 0
                )
            )

        val hasNew = when {
            isLatestCompleted -> false
            releasedCount > item.knownEpisodeCount -> true
            else -> item.hasNewEpisode
        }
        val newKnownCount = if (isLatestCompleted) {
            maxOf(item.knownEpisodeCount, releasedCount)
        } else {
            item.knownEpisodeCount
        }

        return item.copy(
            lastWatchedSeason = lastWatchedSeason,
            lastWatchedEpisode = lastWatchedEpisode,
            knownEpisodeCount = newKnownCount,
            latestAvailableSeason = latestSeason,
            latestAvailableEpisode = latestEpisodeNum,
            latestEpisodeCount = maxOf(item.latestEpisodeCount, releasedCount),
            hasNewEpisode = hasNew,
            lastCheckedAt = nowMs
        )
    }

    fun onEpisodeMarkedCompleted(
        item: WatchlistItem,
        season: Int?,
        episode: Int?,
        releasedCount: Int?,
        nowMs: Long
    ): WatchlistItem {
        val shouldAdvance = season != null && episode != null && (
            item.lastWatchedSeason == null || item.lastWatchedEpisode == null ||
                compareEpisodeOrder(season, episode, item.lastWatchedSeason, item.lastWatchedEpisode) >= 0
            )
        val updatedSeason = if (shouldAdvance) season else item.lastWatchedSeason
        val updatedEpisode = if (shouldAdvance) episode else item.lastWatchedEpisode
        val updatedKnownCount = maxOf(item.knownEpisodeCount, releasedCount ?: item.knownEpisodeCount)

        val completedLatest = when {
            item.latestAvailableSeason != null && item.latestAvailableEpisode != null && season != null && episode != null ->
                compareEpisodeOrder(season, episode, item.latestAvailableSeason, item.latestAvailableEpisode) >= 0
            item.latestAvailableEpisode == null && season != null && episode != null -> true
            else -> false
        }

        return item.copy(
            lastWatchedSeason = updatedSeason,
            lastWatchedEpisode = updatedEpisode,
            knownEpisodeCount = updatedKnownCount,
            hasNewEpisode = if (completedLatest) false else item.hasNewEpisode,
            lastCheckedAt = maxOf(item.lastCheckedAt, nowMs)
        )
    }

    fun onEpisodeUnmarked(
        item: WatchlistItem,
        remainingProgress: List<WatchProgress>,
        nowMs: Long
    ): WatchlistItem {
        val highestCompleted = remainingProgress
            .filter { it.isCompleted && it.seasonNumber != null && it.episodeNumber != null }
            .maxWithOrNull(compareBy<WatchProgress>({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))

        val lastWatchedSeason = highestCompleted?.seasonNumber
        val lastWatchedEpisode = highestCompleted?.episodeNumber

        val isLatestCompleted = item.latestAvailableSeason != null && item.latestAvailableEpisode != null &&
            lastWatchedSeason != null && lastWatchedEpisode != null &&
            compareEpisodeOrder(
                lastWatchedSeason,
                lastWatchedEpisode,
                item.latestAvailableSeason,
                item.latestAvailableEpisode
            ) >= 0

        val hasNew = when {
            isLatestCompleted -> false
            item.latestEpisodeCount > item.knownEpisodeCount -> true
            else -> false
        }

        return item.copy(
            lastWatchedSeason = lastWatchedSeason,
            lastWatchedEpisode = lastWatchedEpisode,
            hasNewEpisode = hasNew,
            lastCheckedAt = maxOf(item.lastCheckedAt, nowMs)
        )
    }

    fun sortWatchlistWithPriority(items: List<WatchlistItem>): List<WatchlistItem> =
        items.sortedWith(
            compareByDescending<WatchlistItem> { it.hasNewEpisode }
                .thenByDescending { if (it.hasNewEpisode) maxOf(it.lastCheckedAt, it.addedAt) else it.addedAt }
        )

    private fun compareEpisodeOrder(s1: Int, e1: Int, s2: Int, e2: Int): Int =
        if (s1 != s2) s1.compareTo(s2) else e1.compareTo(e2)
}

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
