package com.atrishub.atrisstream.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_sources")
data class InstalledSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val baseUrl: String,
    val manifestUrl: String?,
    val driverType: String,
    val enabled: Boolean,
    val isBuiltIn: Boolean,
    val supportedTypesJson: String,
    val languagesJson: String,
    val rawConfig: String?,
    val addedAt: Long
)
