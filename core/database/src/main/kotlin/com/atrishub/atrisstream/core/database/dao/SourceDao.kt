package com.atrishub.atrisstream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.atrishub.atrisstream.core.database.entity.InstalledSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM installed_sources ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<InstalledSourceEntity>>

    @Query("SELECT * FROM installed_sources WHERE enabled = 1 ORDER BY addedAt ASC")
    fun observeEnabled(): Flow<List<InstalledSourceEntity>>

    @Query("SELECT * FROM installed_sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InstalledSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstalledSourceEntity)

    @Query("UPDATE installed_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM installed_sources WHERE id = :id")
    suspend fun deleteById(id: String)
}
