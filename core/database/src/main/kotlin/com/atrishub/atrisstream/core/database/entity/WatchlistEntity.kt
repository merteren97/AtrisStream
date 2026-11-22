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
    val addedAt: Long
) {
    companion object {
        fun buildKeyId(sourceId: String, mediaId: String): String = "$sourceId:$mediaId"
    }
}
