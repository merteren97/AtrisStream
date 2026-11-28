package com.atrishub.atrisstream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atrishub.atrisstream.core.database.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    suspend fun getAll(): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist WHERE keyId = :keyId LIMIT 1")
    suspend fun getByKey(keyId: String): WatchlistEntity?

    suspend fun getByKey(sourceId: String, mediaId: String): WatchlistEntity? =
        getByKey(WatchlistEntity.buildKeyId(sourceId, mediaId))

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE keyId = :keyId)")
    fun observeIsSavedByKey(keyId: String): Flow<Boolean>

    fun observeIsSaved(sourceId: String, mediaId: String): Flow<Boolean> =
        observeIsSavedByKey(WatchlistEntity.buildKeyId(sourceId, mediaId))

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE keyId = :keyId")
    suspend fun deleteByKey(keyId: String)

    suspend fun delete(sourceId: String, mediaId: String) {
        deleteByKey(WatchlistEntity.buildKeyId(sourceId, mediaId))
    }
}
