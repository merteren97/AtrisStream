package com.atrishub.atrisstream.core.model

/**
 * Unified contract implemented by all built-in, JSON-manifest, and QuickJS source providers.
 */
interface MediaProvider {
    val manifest: ProviderManifest

    suspend fun getHome(): List<HomeSection>

    suspend fun browse(type: MediaType, page: Int = 1): PagedResult<MediaCard>

    suspend fun search(query: String, page: Int = 1): PagedResult<MediaCard>

    suspend fun getDetails(id: String, type: MediaType): MediaDetails

    suspend fun getEpisodes(id: String, season: Int): List<Episode>

    suspend fun getStreams(
        mediaId: String,
        type: MediaType,
        season: Int? = null,
        episode: Int? = null
    ): StreamResult
}
