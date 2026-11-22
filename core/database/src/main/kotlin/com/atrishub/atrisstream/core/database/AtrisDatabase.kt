package com.atrishub.atrisstream.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.atrishub.atrisstream.core.database.dao.SourceDao
import com.atrishub.atrisstream.core.database.dao.WatchProgressDao
import com.atrishub.atrisstream.core.database.dao.WatchlistDao
import com.atrishub.atrisstream.core.database.entity.InstalledSourceEntity
import com.atrishub.atrisstream.core.database.entity.WatchProgressEntity
import com.atrishub.atrisstream.core.database.entity.WatchlistEntity

@Database(
    entities = [
        InstalledSourceEntity::class,
        WatchProgressEntity::class,
        WatchlistEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AtrisDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun watchlistDao(): WatchlistDao

    companion object {
        @Volatile
        private var instance: AtrisDatabase? = null

        fun getInstance(context: Context): AtrisDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AtrisDatabase::class.java,
                    "atris_stream.db"
                ).build().also { instance = it }
            }
    }
}
