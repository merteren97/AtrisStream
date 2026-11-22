package com.atrishub.atrisstream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atrishub.atrisstream.core.database.entity.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeContinueWatching(limit: Int = 20): Flow<List<WatchProgressEntity>>

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
