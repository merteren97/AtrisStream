package com.atrishub.atrisstream.core.database.mapper

import com.atrishub.atrisstream.core.database.entity.InstalledSourceEntity
import com.atrishub.atrisstream.core.database.entity.WatchProgressEntity
import com.atrishub.atrisstream.core.database.entity.WatchlistEntity
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.SourceDriverType
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.model.WatchlistItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val mapperJson = Json { ignoreUnknownKeys = true }

fun InstalledSourceEntity.toModel(): InstalledSource = InstalledSource(
    id = id,
    name = name,
    version = version,
    baseUrl = baseUrl,
    manifestUrl = manifestUrl,
    driverType = runCatching { SourceDriverType.valueOf(driverType) }
        .getOrDefault(SourceDriverType.JSON_MANIFEST),
    enabled = enabled,
    isBuiltIn = isBuiltIn,
    supportedTypes = runCatching { mapperJson.decodeFromString<List<MediaType>>(supportedTypesJson) }
        .getOrDefault(listOf(MediaType.MOVIE, MediaType.TV)),
    languages = runCatching { mapperJson.decodeFromString<List<String>>(languagesJson) }
        .getOrDefault(listOf("en")),
    rawConfig = rawConfig,
    addedAt = addedAt
)

fun InstalledSource.toEntity(): InstalledSourceEntity = InstalledSourceEntity(
    id = id,
    name = name,
    version = version,
    baseUrl = baseUrl,
    manifestUrl = manifestUrl,
    driverType = driverType.name,
    enabled = enabled,
    isBuiltIn = isBuiltIn,
    supportedTypesJson = mapperJson.encodeToString(supportedTypes),
    languagesJson = mapperJson.encodeToString(languages),
    rawConfig = rawConfig,
    addedAt = addedAt
)

fun WatchProgressEntity.toModel(): WatchProgress = WatchProgress(
    mediaId = mediaId,
    sourceId = sourceId,
    type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.MOVIE),
    title = title,
    posterUrl = posterUrl,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    isCompleted = isCompleted
)

fun WatchProgress.toEntity(): WatchProgressEntity = WatchProgressEntity(
    keyId = WatchProgressEntity.buildKeyId(
        sourceId = sourceId,
        mediaId = mediaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber
    ),
    sourceId = sourceId,
    mediaId = mediaId,
    type = type.name,
    title = title,
    posterUrl = posterUrl,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    isCompleted = isCompleted
)

fun WatchlistEntity.toModel(): WatchlistItem = WatchlistItem(
    mediaId = mediaId,
    sourceId = sourceId,
    type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.MOVIE),
    title = title,
    posterUrl = posterUrl,
    year = year,
    rating = rating,
    addedAt = addedAt
)

fun WatchlistItem.toEntity(): WatchlistEntity = WatchlistEntity(
    keyId = WatchlistEntity.buildKeyId(sourceId = sourceId, mediaId = mediaId),
    sourceId = sourceId,
    mediaId = mediaId,
    type = type.name,
    title = title,
    posterUrl = posterUrl,
    year = year,
    rating = rating,
    addedAt = addedAt
)
