package com.atrishub.atrisstream.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val keyId: String,
    val sourceId: String,
    val mediaId: String,
    val type: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Double?,
    val addedAt: Long,
    val lastWatchedSeason: Int? = null,
    val lastWatchedEpisode: Int? = null,
    val knownEpisodeCount: Int = 0,
    val latestAvailableSeason: Int? = null,
    val latestAvailableEpisode: Int? = null,
    val latestEpisodeCount: Int = 0,
    val hasNewEpisode: Boolean = false,
    val lastCheckedAt: Long = 0L
) {
    companion object {
        fun buildKeyId(sourceId: String, mediaId: String): String = "$sourceId:$mediaId"
    }
}
