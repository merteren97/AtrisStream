package com.atrishub.atrisstream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atrishub.atrisstream.core.database.entity.WatchProgressEntity
import com.atrishub.atrisstream.core.model.WatchProgressPolicy
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Query(
        """
        SELECT wp.* FROM watch_progress wp
        INNER JOIN (
            SELECT sourceId, mediaId, MAX(updatedAt) AS maxUpdatedAt
            FROM watch_progress
            WHERE isCompleted = 0
              AND positionMs >= :minPositionMs
              AND type != 'LIVE'
            GROUP BY sourceId, mediaId
        ) latest
        ON wp.sourceId = latest.sourceId
           AND wp.mediaId = latest.mediaId
           AND wp.updatedAt = latest.maxUpdatedAt
        WHERE wp.isCompleted = 0
          AND wp.positionMs >= :minPositionMs
          AND wp.type != 'LIVE'
        ORDER BY wp.updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeContinueWatching(
        limit: Int = 20,
        minPositionMs: Long = WatchProgressPolicy.MIN_RESUME_POSITION_MS
    ): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC")
    suspend fun getAll(): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE sourceId = :sourceId AND mediaId = :mediaId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestProgressForMedia(sourceId: String, mediaId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE sourceId = :sourceId AND mediaId = :mediaId ORDER BY updatedAt DESC")
    suspend fun getAllProgressForMedia(sourceId: String, mediaId: String): List<WatchProgressEntity>

    @Query("UPDATE watch_progress SET isCompleted = 1, updatedAt = :updatedAt WHERE keyId = :keyId")
    suspend fun markCompleted(keyId: String, updatedAt: Long)

    @Query("SELECT * FROM watch_progress WHERE keyId = :keyId LIMIT 1")
    suspend fun getByKeyId(keyId: String): WatchProgressEntity?

    suspend fun getProgress(
        sourceId: String,
        mediaId: String,
        season: Int? = null,
        episode: Int? = null
    ): WatchProgressEntity? = getByKeyId(
        WatchProgressEntity.buildKeyId(sourceId, mediaId, season, episode)
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE keyId = :keyId")
    suspend fun delete(keyId: String)
}
