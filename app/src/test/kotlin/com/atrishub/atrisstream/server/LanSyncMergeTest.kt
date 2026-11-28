package com.atrishub.atrisstream.server

import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.SourceDriverType
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.model.WatchlistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSyncMergeTest {

    @Test
    fun mergeWatchProgress_newerRemoteWins() {
        val local = listOf(
            WatchProgress(
                mediaId = "movie-1",
                sourceId = "movy.sx",
                type = MediaType.MOVIE,
                title = "Inception",
                positionMs = 100_000L,
                durationMs = 600_000L,
                updatedAt = 1_000L,
                isCompleted = false
            )
        )
        val incoming = listOf(
            WatchProgress(
                mediaId = "movie-1",
                sourceId = "movy.sx",
                type = MediaType.MOVIE,
                title = "Inception",
                positionMs = 450_000L,
                durationMs = 600_000L,
                updatedAt = 2_000L,
                isCompleted = false
            )
        )

        val outcome = LanSyncMerger.mergeWatchProgress(local, incoming)

        assertEquals(1, outcome.toUpsert.size)
        assertEquals(450_000L, outcome.toUpsert.first().positionMs)
        assertEquals(1, outcome.merged.size)
        assertEquals(2_000L, outcome.merged.first().updatedAt)
    }

    @Test
    fun mergeWatchProgress_olderRemoteDoesNotOverwriteNewerLocal() {
        val local = listOf(
            WatchProgress(
                mediaId = "ep-1",
                sourceId = "movy.sx",
                type = MediaType.TV,
                title = "Severance",
                seasonNumber = 1,
                episodeNumber = 3,
                positionMs = 500_000L,
                durationMs = 3_000_000L,
                updatedAt = 5_000L,
                isCompleted = false
            )
        )
        val incoming = listOf(
            WatchProgress(
                mediaId = "ep-1",
                sourceId = "movy.sx",
                type = MediaType.TV,
                title = "Severance",
                seasonNumber = 1,
                episodeNumber = 3,
                positionMs = 120_000L,
                durationMs = 3_000_000L,
                updatedAt = 3_000L,
                isCompleted = true
            )
        )

        val outcome = LanSyncMerger.mergeWatchProgress(local, incoming)

        assertTrue(outcome.toUpsert.isEmpty())
        assertEquals(1, outcome.merged.size)
        assertEquals(500_000L, outcome.merged.first().positionMs)
        assertFalse(outcome.merged.first().isCompleted)
    }

    @Test
    fun mergeWatchProgress_equalTimestampCompletedTieBreaker() {
        val local = listOf(
            WatchProgress(
                mediaId = "movie-2",
                sourceId = "movy.sx",
                type = MediaType.MOVIE,
                title = "Dune",
                positionMs = 580_000L,
                durationMs = 600_000L,
                updatedAt = 4_000L,
                isCompleted = false
            ),
            WatchProgress(
                mediaId = "movie-3",
                sourceId = "movy.sx",
                type = MediaType.MOVIE,
                title = "Arrival",
                positionMs = 600_000L,
                durationMs = 600_000L,
                updatedAt = 4_000L,
                isCompleted = true
            )
        )
        val incoming = listOf(
            WatchProgress(
                mediaId = "movie-2",
                sourceId = "movy.sx",
                type = MediaType.MOVIE,
                title = "Dune",
                positionMs = 590_000L,
                durationMs = 600_000L,
                updatedAt = 4_000L,
                isCompleted = true
            ),
            WatchProgress(
                mediaId = "movie-3",
                sourceId = "movy.sx",
                type = MediaType.MOVIE,
                title = "Arrival",
                positionMs = 300_000L,
                durationMs = 600_000L,
                updatedAt = 4_000L,
                isCompleted = false
            )
        )

        val outcome = LanSyncMerger.mergeWatchProgress(local, incoming)

        assertEquals(1, outcome.toUpsert.size)
        assertEquals("movie-2", outcome.toUpsert.first().mediaId)
        assertTrue(outcome.merged.first { it.mediaId == "movie-2" }.isCompleted)
        assertTrue(outcome.merged.first { it.mediaId == "movie-3" }.isCompleted)
    }

    @Test
    fun mergeWatchlist_unionsNewItemsAndUpdatesOnlyNewerAddedAt() {
        val local = listOf(
            WatchlistItem(
                mediaId = "m1",
                sourceId = "movy.sx",
                type = MediaType.MOVIE,
                title = "Local Newer",
                addedAt = 10_000L
            ),
            WatchlistItem(
                mediaId = "m2",
                sourceId = "movy.sx",
                type = MediaType.TV,
                title = "Local Older",
                addedAt = 1_000L
            )
        )
        val incoming = listOf(
            WatchlistItem(
                mediaId = "m1",
                sourceId = "movy.sx",
                type = MediaType.MOVIE,
                title = "Remote Older",
                addedAt = 5_000L
            ),
            WatchlistItem(
                mediaId = "m2",
                sourceId = "movy.sx",
                type = MediaType.TV,
                title = "Remote Newer",
                addedAt = 8_000L
            ),
            WatchlistItem(
                mediaId = "m3",
                sourceId = "custom-src",
                type = MediaType.ANIME,
                title = "Brand New",
                addedAt = 9_000L
            )
        )

        val outcome = LanSyncMerger.mergeWatchlist(local, incoming)

        assertEquals(listOf("m2", "m3"), outcome.toUpsert.map { it.mediaId })
        assertEquals(3, outcome.merged.size)
        assertEquals("Local Newer", outcome.merged.first { it.mediaId == "m1" }.title)
        assertEquals("Remote Newer", outcome.merged.first { it.mediaId == "m2" }.title)
    }

    @Test
    fun mergeWatchlist_equalAddedAtUsesNewerLastCheckedAtTieBreaker() {
        val local = listOf(
            WatchlistItem(
                mediaId = "anime-1",
                sourceId = "builtin_reanime",
                type = MediaType.ANIME,
                title = "Frieren",
                addedAt = 10_000L,
                knownEpisodeCount = 10,
                hasNewEpisode = false,
                lastCheckedAt = 20_000L
            )
        )
        val incoming = listOf(
            WatchlistItem(
                mediaId = "anime-1",
                sourceId = "builtin_reanime",
                type = MediaType.ANIME,
                title = "Frieren",
                addedAt = 10_000L,
                knownEpisodeCount = 10,
                latestAvailableSeason = 1,
                latestAvailableEpisode = 11,
                latestEpisodeCount = 11,
                hasNewEpisode = true,
                lastCheckedAt = 30_000L
            )
        )

        val outcome = LanSyncMerger.mergeWatchlist(local, incoming)

        assertEquals(1, outcome.toUpsert.size)
        val merged = outcome.merged.single()
        assertTrue(merged.hasNewEpisode)
        assertEquals(11, merged.latestAvailableEpisode)
        assertEquals(30_000L, merged.lastCheckedAt)
    }

    @Test
    fun mergeSources_mergesNonBuiltInWithoutOverwritingBuiltInMovy() {
        val builtInMovy = InstalledSource(
            id = "movy.sx",
            name = "Movy",
            version = "1.0.0",
            baseUrl = "https://movy.sx",
            driverType = SourceDriverType.BUILTIN,
            enabled = true,
            isBuiltIn = true,
            addedAt = 1_000L
        )
        val existingCustom = InstalledSource(
            id = "custom-1",
            name = "Custom One",
            version = "1.0.0",
            baseUrl = "https://example.com/1.json",
            driverType = SourceDriverType.JSON_MANIFEST,
            enabled = true,
            isBuiltIn = false,
            addedAt = 2_000L
        )
        val remoteMovyFake = builtInMovy.copy(
            name = "Hijacked Movy",
            baseUrl = "https://evil.example",
            isBuiltIn = false,
            addedAt = 99_999L
        )
        val remoteMovyBuiltIn = builtInMovy.copy(
            name = "Remote BuiltIn Movy",
            addedAt = 99_999L
        )
        val remoteNewCustom = InstalledSource(
            id = "custom-2",
            name = "Custom Two",
            version = "1.2.0",
            baseUrl = "https://example.com/2.json",
            driverType = SourceDriverType.JS_PLUGIN,
            enabled = true,
            isBuiltIn = false,
            addedAt = 5_000L
        )
        val remoteUpdatedCustom = existingCustom.copy(
            version = "1.1.0",
            addedAt = 6_000L
        )

        val outcome = LanSyncMerger.mergeSources(
            local = listOf(builtInMovy, existingCustom),
            incoming = listOf(remoteMovyFake, remoteMovyBuiltIn, remoteNewCustom, remoteUpdatedCustom)
        )

        assertEquals(listOf("custom-2", "custom-1"), outcome.toUpsert.map { it.id })
        assertEquals(3, outcome.merged.size)
        val movy = outcome.merged.first { it.id == "movy.sx" }
        assertEquals("Movy", movy.name)
        assertTrue(movy.isBuiltIn)
        assertEquals("1.1.0", outcome.merged.first { it.id == "custom-1" }.version)
    }
}
