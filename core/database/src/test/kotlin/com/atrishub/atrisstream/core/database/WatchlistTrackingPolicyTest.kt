package com.atrishub.atrisstream.core.database

import com.atrishub.atrisstream.core.database.mapper.toEntity
import com.atrishub.atrisstream.core.database.mapper.toModel
import com.atrishub.atrisstream.core.model.Episode
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.model.WatchlistItem
import com.atrishub.atrisstream.core.model.WatchlistTrackingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistTrackingPolicyTest {

    private fun baseItem(knownEpisodeCount: Int = 0) = WatchlistItem(
        mediaId = "frieren",
        sourceId = "builtin_reanime",
        type = MediaType.ANIME,
        title = "Frieren",
        addedAt = 1_000L,
        knownEpisodeCount = knownEpisodeCount
    )

    private fun ep(num: Int, released: Boolean = true) = Episode(
        id = "ep-$num",
        seasonNumber = 1,
        episodeNumber = num,
        title = "Episode $num",
        isReleased = released
    )

    @Test
    fun `initializes knownEpisodeCount without false alarm when zero`() {
        val episodes = listOf(ep(1), ep(2), ep(3), ep(4, released = false))
        val updated = WatchlistTrackingPolicy.evaluateWatchlistUpdate(
            item = baseItem(knownEpisodeCount = 0),
            releasedEpisodes = episodes,
            progressList = emptyList(),
            nowMs = 5_000L
        )
        assertEquals(3, updated.knownEpisodeCount)
        assertEquals(3, updated.latestEpisodeCount)
        assertEquals(1, updated.latestAvailableSeason)
        assertEquals(3, updated.latestAvailableEpisode)
        assertFalse(updated.hasNewEpisode)
        assertEquals(5_000L, updated.lastCheckedAt)
    }

    @Test
    fun `flags hasNewEpisode when released count exceeds knownEpisodeCount`() {
        val initial = baseItem(knownEpisodeCount = 3).copy(
            lastWatchedSeason = 1,
            lastWatchedEpisode = 3
        )
        val episodes = listOf(ep(1), ep(2), ep(3), ep(4), ep(5, released = false))
        val progress = listOf(
            WatchProgress(
                mediaId = "frieren",
                sourceId = "builtin_reanime",
                type = MediaType.ANIME,
                title = "Frieren",
                seasonNumber = 1,
                episodeNumber = 3,
                isCompleted = true
            )
        )
        val updated = WatchlistTrackingPolicy.evaluateWatchlistUpdate(
            item = initial,
            releasedEpisodes = episodes,
            progressList = progress,
            nowMs = 10_000L
        )
        assertTrue(updated.hasNewEpisode)
        assertEquals(1, updated.latestAvailableSeason)
        assertEquals(4, updated.latestAvailableEpisode)
        assertEquals(4, updated.latestEpisodeCount)
        assertEquals(3, updated.knownEpisodeCount)
    }

    @Test
    fun `onEpisodeMarkedCompleted advances progress and clears hasNewEpisode`() {
        val flagged = baseItem(knownEpisodeCount = 3).copy(
            lastWatchedSeason = 1,
            lastWatchedEpisode = 3,
            latestAvailableSeason = 1,
            latestAvailableEpisode = 4,
            latestEpisodeCount = 4,
            hasNewEpisode = true
        )
        val updated = WatchlistTrackingPolicy.onEpisodeMarkedCompleted(
            item = flagged,
            season = 1,
            episode = 4,
            releasedCount = 4,
            nowMs = 12_000L
        )
        assertFalse(updated.hasNewEpisode)
        assertEquals(1, updated.lastWatchedSeason)
        assertEquals(4, updated.lastWatchedEpisode)
        assertEquals(4, updated.knownEpisodeCount)
    }

    @Test
    fun `sortWatchlistWithPriority places hasNewEpisode items first`() {
        val normalNewer = baseItem().copy(mediaId = "a", addedAt = 5_000L, hasNewEpisode = false)
        val flaggedOlder = baseItem().copy(mediaId = "b", addedAt = 1_000L, hasNewEpisode = true, lastCheckedAt = 2_000L)
        val normalOlder = baseItem().copy(mediaId = "c", addedAt = 2_000L, hasNewEpisode = false)

        val sorted = WatchlistTrackingPolicy.sortWatchlistWithPriority(
            listOf(normalOlder, normalNewer, flaggedOlder)
        )
        assertEquals(listOf("b", "a", "c"), sorted.map { it.mediaId })
    }

    @Test
    fun `watchlist entity round-trips all tracking columns`() {
        val item = WatchlistItem(
            mediaId = "solo-leveling",
            sourceId = "builtin_animex",
            type = MediaType.ANIME,
            title = "Solo Leveling",
            addedAt = 100L,
            lastWatchedSeason = 1,
            lastWatchedEpisode = 10,
            knownEpisodeCount = 10,
            latestAvailableSeason = 1,
            latestAvailableEpisode = 12,
            latestEpisodeCount = 12,
            hasNewEpisode = true,
            lastCheckedAt = 999L
        )
        assertEquals(item, item.toEntity().toModel())
    }

    @Test
    fun `onEpisodeUnmarked recalculates lastWatched from remaining progress and restores hasNewEpisode`() {
        val flaggedThenCompleted = baseItem(knownEpisodeCount = 3).copy(
            lastWatchedSeason = 1,
            lastWatchedEpisode = 4,
            latestAvailableSeason = 1,
            latestAvailableEpisode = 4,
            latestEpisodeCount = 4,
            hasNewEpisode = false
        )
        val remainingWithEp3 = listOf(
            WatchProgress(
                mediaId = "frieren",
                sourceId = "builtin_reanime",
                type = MediaType.ANIME,
                title = "Frieren",
                seasonNumber = 1,
                episodeNumber = 3,
                isCompleted = true
            )
        )
        val unmarked = WatchlistTrackingPolicy.onEpisodeUnmarked(
            item = flaggedThenCompleted,
            remainingProgress = remainingWithEp3,
            nowMs = 15_000L
        )
        assertEquals(1, unmarked.lastWatchedSeason)
        assertEquals(3, unmarked.lastWatchedEpisode)
        assertTrue(unmarked.hasNewEpisode)

        val unmarkedAll = WatchlistTrackingPolicy.onEpisodeUnmarked(
            item = unmarked,
            remainingProgress = emptyList(),
            nowMs = 16_000L
        )
        assertEquals(null, unmarkedAll.lastWatchedSeason)
        assertEquals(null, unmarkedAll.lastWatchedEpisode)
        assertTrue(unmarkedAll.hasNewEpisode)
    }

    @Test
    fun `evaluateWatchlistUpdate clears stale lastWatched when progressList has no completed episodes`() {
        val stale = baseItem(knownEpisodeCount = 3).copy(
            lastWatchedSeason = 1,
            lastWatchedEpisode = 3
        )
        val evaluated = WatchlistTrackingPolicy.evaluateWatchlistUpdate(
            item = stale,
            releasedEpisodes = listOf(ep(1), ep(2), ep(3)),
            progressList = emptyList(),
            nowMs = 20_000L
        )
        assertEquals(null, evaluated.lastWatchedSeason)
        assertEquals(null, evaluated.lastWatchedEpisode)
    }
}
