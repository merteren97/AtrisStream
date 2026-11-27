package com.atrishub.atrisstream.core.database

import com.atrishub.atrisstream.core.database.entity.WatchProgressEntity
import com.atrishub.atrisstream.core.database.entity.WatchlistEntity
import com.atrishub.atrisstream.core.database.mapper.toEntity
import com.atrishub.atrisstream.core.database.mapper.toModel
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.SourceDriverType
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.model.WatchlistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DatabaseMappingTest {

    @Test
    fun `watch progress keyId is deterministic for movies and episodes`() {
        val movieKey1 = WatchProgressEntity.buildKeyId("movy-sx", "inception-2010")
        val movieKey2 = WatchProgressEntity.buildKeyId("movy-sx", "inception-2010", null, null)
        assertEquals(movieKey1, movieKey2)
        assertEquals("movy-sx:inception-2010:-1:-1", movieKey1)

        val ep1Key = WatchProgressEntity.buildKeyId("movy-sx", "breaking-bad", 1, 1)
        val ep2Key = WatchProgressEntity.buildKeyId("movy-sx", "breaking-bad", 1, 2)
        assertNotEquals(ep1Key, ep2Key)
        assertEquals("movy-sx:breaking-bad:1:1", ep1Key)
    }

    @Test
    fun `watchlist keyId is deterministic`() {
        assertEquals("movy-sx:tt1375666", WatchlistEntity.buildKeyId("movy-sx", "tt1375666"))
    }

    @Test
    fun `installed source round-trips between model and entity`() {
        val source = InstalledSource(
            id = "movy-sx",
            name = "Movy.sx",
            version = "1.0.0",
            baseUrl = "https://www.movy.sx/tr",
            manifestUrl = "https://example.com/movy.json",
            driverType = SourceDriverType.BUILTIN,
            enabled = true,
            isBuiltIn = true,
            supportedTypes = listOf(MediaType.MOVIE, MediaType.TV, MediaType.ANIME),
            languages = listOf("tr", "en"),
            rawConfig = """{"timeout":20}""",
            addedAt = 1_700_000_000L
        )

        val roundTripped = source.toEntity().toModel()
        assertEquals(source, roundTripped)
    }

    @Test
    fun `watch progress round-trips between model and entity`() {
        val inProgress = WatchProgress(
            mediaId = "breaking-bad",
            sourceId = "movy-sx",
            type = MediaType.TV,
            title = "Breaking Bad",
            posterUrl = "https://cdn.example.com/bb.jpg",
            seasonNumber = 2,
            episodeNumber = 5,
            positionMs = 123_456L,
            durationMs = 2_800_000L,
            updatedAt = 1_700_000_123L,
            isCompleted = false
        )

        val inProgressEntity = inProgress.toEntity()
        assertEquals("movy-sx:breaking-bad:2:5", inProgressEntity.keyId)
        assertEquals(false, inProgressEntity.isCompleted)
        assertEquals(inProgress, inProgressEntity.toModel())

        val completed = inProgress.copy(
            positionMs = 2_600_000L,
            isCompleted = true
        )
        val completedEntity = completed.toEntity()
        assertEquals(true, completedEntity.isCompleted)
        assertEquals(completed, completedEntity.toModel())
    }

    @Test
    fun `watchlist item round-trips between model and entity`() {
        val item = WatchlistItem(
            mediaId = "dune-2",
            sourceId = "movy-sx",
            type = MediaType.MOVIE,
            title = "Dune: Part Two",
            posterUrl = "https://cdn.example.com/dune2.jpg",
            year = 2024,
            rating = 8.6,
            addedAt = 1_700_000_999L
        )

        val entity = item.toEntity()
        assertEquals("movy-sx:dune-2", entity.keyId)
        assertEquals(item, entity.toModel())
    }
}
