package com.atrishub.atrisstream.core.model

import kotlinx.serialization.Serializable

/**
 * Execution driver backing a [MediaProvider] source.
 */
@Serializable
enum class SourceDriverType {
    BUILTIN,
    JSON_MANIFEST,
    JS_PLUGIN
}

/**
 * Public metadata describing a source provider.
 */
@Serializable
data class ProviderManifest(
    val id: String,
    val name: String,
    val version: String,
    val baseUrl: String,
    val supportedTypes: List<MediaType> = listOf(MediaType.MOVIE, MediaType.TV),
    val languages: List<String> = listOf("en")
)

/**
 * Persisted source configuration managed in the Resources / Kaynaklar screen.
 */
@Serializable
data class InstalledSource(
    val id: String,
    val name: String,
    val version: String,
    val baseUrl: String,
    val manifestUrl: String? = null,
    val driverType: SourceDriverType,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val supportedTypes: List<MediaType> = listOf(MediaType.MOVIE, MediaType.TV),
    val languages: List<String> = listOf("en"),
    val rawConfig: String? = null,
    val addedAt: Long = 0L
)
