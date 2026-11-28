package com.atrishub.atrisstream.core.model

import kotlinx.serialization.Serializable

/**
 * Supported media categories across AtrisStream providers.
 */
@Serializable
enum class MediaType {
    MOVIE,
    TV,
    ANIME,
    LIVE
}

/**
 * Compact media item displayed in home rows, browse grids, and search results.
 */
@Serializable
data class MediaCard(
    val id: String,
    val sourceId: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val type: MediaType,
    val year: Int? = null,
    val rating: Double? = null,
    val subtitle: String? = null
)

/**
 * A titled horizontal row of media cards on the home screen.
 */
@Serializable
data class HomeSection(
    val title: String,
    val items: List<MediaCard>
)

/**
 * Generic paginated response wrapper for browse and search operations.
 */
@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val page: Int,
    val hasNextPage: Boolean
)

/**
 * Full metadata for a movie, series, anime, or live channel.
 */
@Serializable
data class MediaDetails(
    val id: String,
    val sourceId: String,
    val title: String,
    val type: MediaType,
    val description: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val seasons: List<Season> = emptyList()
)

/**
 * Represents a season within a TV series or anime.
 */
@Serializable
data class Season(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int? = null,
    val posterUrl: String? = null,
    val episodes: List<Episode> = emptyList()
)

/**
 * Represents a single playable episode in a season.
 */
@Serializable
data class Episode(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String? = null,
    val thumbnailUrl: String? = null,
    val runtimeMinutes: Int? = null,
    val isReleased: Boolean = true
)
