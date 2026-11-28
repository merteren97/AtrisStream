package com.atrishub.atrisstream.core.database.repository

import com.atrishub.atrisstream.core.database.AtrisDatabase
import com.atrishub.atrisstream.core.database.entity.WatchProgressEntity
import com.atrishub.atrisstream.core.database.mapper.toEntity
import com.atrishub.atrisstream.core.database.mapper.toModel
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.model.WatchProgressPolicy
import com.atrishub.atrisstream.core.model.WatchlistItem
import com.atrishub.atrisstream.core.model.WatchlistTrackingPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Facade repository exposing installed sources, watch history, and watchlist persistence
 * mapped cleanly to `:core:model` types.
 */
class AtrisRepository(
    private val database: AtrisDatabase
) {
    private val sourceDao = database.sourceDao()
    private val watchProgressDao = database.watchProgressDao()
    private val watchlistDao = database.watchlistDao()

    // --- Sources ---

    fun observeAllSources(): Flow<List<InstalledSource>> =
        sourceDao.observeAll().map { entities -> entities.map { it.toModel() } }

    fun observeEnabledSources(): Flow<List<InstalledSource>> =
        sourceDao.observeEnabled().map { entities -> entities.map { it.toModel() } }

    suspend fun getSourceById(id: String): InstalledSource? =
        sourceDao.getById(id)?.toModel()

    suspend fun upsertSource(source: InstalledSource) {
        sourceDao.upsert(source.toEntity())
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        sourceDao.setEnabled(id, enabled)
    }

    suspend fun deleteSource(id: String) {
        sourceDao.deleteById(id)
    }

    // --- Watch Progress ---

    fun observeContinueWatching(limit: Int = 20): Flow<List<WatchProgress>> =
        watchProgressDao.observeContinueWatching(
            limit = limit,
            minPositionMs = WatchProgressPolicy.MIN_RESUME_POSITION_MS
        ).map { entities ->
            entities.map { it.toModel() }
                .distinctBy { it.sourceId to it.mediaId }
                .take(limit)
        }

    suspend fun getAllWatchProgress(): List<WatchProgress> =
        watchProgressDao.getAll().map { it.toModel() }

    suspend fun getLatestProgressForMedia(sourceId: String, mediaId: String): WatchProgress? =
        watchProgressDao.getLatestProgressForMedia(sourceId, mediaId)?.toModel()

    suspend fun getAllProgressForMedia(sourceId: String, mediaId: String): List<WatchProgress> =
        watchProgressDao.getAllProgressForMedia(sourceId, mediaId).map { it.toModel() }

    suspend fun markWatchProgressCompleted(
        sourceId: String,
        mediaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ) {
        val nowMs = System.currentTimeMillis()
        val keyId = WatchProgressEntity.buildKeyId(sourceId, mediaId, seasonNumber, episodeNumber)
        watchProgressDao.markCompleted(keyId, nowMs)
        val saved = watchlistDao.getByKey(sourceId, mediaId)
        if (saved != null) {
            val updated = WatchlistTrackingPolicy.onEpisodeMarkedCompleted(
                item = saved.toModel(),
                season = seasonNumber,
                episode = episodeNumber,
                releasedCount = null,
                nowMs = nowMs
            )
            watchlistDao.upsert(updated.toEntity())
        }
    }

    suspend fun setEpisodeCompleted(
        sourceId: String,
        mediaId: String,
        type: MediaType,
        title: String,
        posterUrl: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        completed: Boolean,
        releasedCount: Int? = null
    ) {
        val nowMs = System.currentTimeMillis()
        val existing = watchProgressDao.getProgress(sourceId, mediaId, seasonNumber, episodeNumber)
        val progress = if (existing != null) {
            existing.toModel().copy(
                title = title,
                posterUrl = posterUrl ?: existing.posterUrl,
                isCompleted = completed,
                updatedAt = nowMs
            )
        } else {
            WatchProgress(
                mediaId = mediaId,
                sourceId = sourceId,
                type = type,
                title = title,
                posterUrl = posterUrl,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                positionMs = 0L,
                durationMs = 0L,
                updatedAt = nowMs,
                isCompleted = completed
            )
        }
        watchProgressDao.upsert(progress.toEntity())

        if (completed) {
            val saved = watchlistDao.getByKey(sourceId, mediaId)
            if (saved != null) {
                val updated = WatchlistTrackingPolicy.onEpisodeMarkedCompleted(
                    item = saved.toModel(),
                    season = seasonNumber,
                    episode = episodeNumber,
                    releasedCount = releasedCount,
                    nowMs = nowMs
                )
                watchlistDao.upsert(updated.toEntity())
            }
        } else {
            val saved = watchlistDao.getByKey(sourceId, mediaId)
            if (saved != null) {
                val remaining = watchProgressDao.getAllProgressForMedia(sourceId, mediaId).map { it.toModel() }
                val updated = WatchlistTrackingPolicy.onEpisodeUnmarked(
                    item = saved.toModel(),
                    remainingProgress = remaining,
                    nowMs = nowMs
                )
                watchlistDao.upsert(updated.toEntity())
            }
        }
    }

    suspend fun getWatchProgress(
        sourceId: String,
        mediaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): WatchProgress? =
        watchProgressDao.getProgress(sourceId, mediaId, seasonNumber, episodeNumber)?.toModel()

    suspend fun upsertWatchProgress(progress: WatchProgress) {
        watchProgressDao.upsert(progress.toEntity())
        if (progress.isCompleted) {
            val saved = watchlistDao.getByKey(progress.sourceId, progress.mediaId)
            if (saved != null) {
                val updated = WatchlistTrackingPolicy.onEpisodeMarkedCompleted(
                    item = saved.toModel(),
                    season = progress.seasonNumber,
                    episode = progress.episodeNumber,
                    releasedCount = null,
                    nowMs = maxOf(progress.updatedAt, System.currentTimeMillis())
                )
                watchlistDao.upsert(updated.toEntity())
            }
        }
    }

    suspend fun deleteWatchProgress(
        sourceId: String,
        mediaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ) {
        val keyId = WatchProgressEntity.buildKeyId(sourceId, mediaId, seasonNumber, episodeNumber)
        watchProgressDao.delete(keyId)
    }

    // --- Watchlist ---

    fun observeWatchlist(): Flow<List<WatchlistItem>> =
        watchlistDao.observeAll().map { entities -> entities.map { it.toModel() } }

    fun observeIsSaved(sourceId: String, mediaId: String): Flow<Boolean> =
        watchlistDao.observeIsSaved(sourceId, mediaId)

    suspend fun getWatchlistItem(sourceId: String, mediaId: String): WatchlistItem? =
        watchlistDao.getByKey(sourceId, mediaId)?.toModel()

    suspend fun getAllWatchlistItems(): List<WatchlistItem> =
        watchlistDao.getAll().map { it.toModel() }

    suspend fun upsertWatchlistItem(item: WatchlistItem) {
        watchlistDao.upsert(item.toEntity())
    }

    suspend fun removeFromWatchlist(sourceId: String, mediaId: String) {
        watchlistDao.delete(sourceId, mediaId)
    }
}
