package com.atrishub.atrisstream.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false
)
abstract class AtrisDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun watchlistDao(): WatchlistDao

    companion object {
        @Volatile
        private var instance: AtrisDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE watch_progress ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    UPDATE watch_progress
                    SET isCompleted = 1
                    WHERE durationMs > 0 AND positionMs > 0 AND (
                        (durationMs < 1800000 AND (
                            CAST(positionMs AS REAL) / durationMs >= 0.88
                            OR ((durationMs - positionMs) <= 150000 AND CAST(positionMs AS REAL) / durationMs >= 0.80)
                        ))
                        OR (durationMs >= 1800000 AND durationMs < 4500000 AND (
                            CAST(positionMs AS REAL) / durationMs >= 0.90
                            OR ((durationMs - positionMs) <= 600000 AND CAST(positionMs AS REAL) / durationMs >= 0.80)
                        ))
                        OR (durationMs >= 4500000 AND (
                            CAST(positionMs AS REAL) / durationMs >= 0.92
                            OR ((durationMs - positionMs) <= 720000 AND CAST(positionMs AS REAL) / durationMs >= 0.85)
                        ))
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AtrisDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AtrisDatabase::class.java,
                    "atris_stream.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
