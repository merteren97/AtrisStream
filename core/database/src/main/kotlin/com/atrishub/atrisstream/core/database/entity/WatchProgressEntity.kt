package com.atrishub.atrisstream.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val keyId: String,
    val sourceId: String,
    val mediaId: String,
    val type: String,
    val title: String,
    val posterUrl: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val isCompleted: Boolean = false
) {
    companion object {
        fun buildKeyId(
            sourceId: String,
            mediaId: String,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null
        ): String = "$sourceId:$mediaId:${seasonNumber ?: -1}:${episodeNumber ?: -1}"
    }
}
