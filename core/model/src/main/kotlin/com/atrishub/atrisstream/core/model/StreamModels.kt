package com.atrishub.atrisstream.core.model

import kotlinx.serialization.Serializable

/**
 * Container/protocol format of a resolved video stream.
 */
@Serializable
enum class StreamFormat {
    HLS,
    DASH,
    MP4,
    UNKNOWN
}

/**
 * A playable video stream URL with quality, server, audio, and HTTP header metadata.
 */
@Serializable
data class StreamSource(
    val url: String,
    val quality: String = "Auto",
    val format: StreamFormat = StreamFormat.UNKNOWN,
    val serverName: String = "Default",
    val audioLang: String? = null,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Supported subtitle file formats.
 */
@Serializable
enum class SubtitleFormat {
    VTT,
    SRT,
    ASS
}

/**
 * A subtitle track associated with a stream or added externally by the user.
 */
@Serializable
data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String,
    val format: SubtitleFormat = SubtitleFormat.VTT,
    val headers: Map<String, String> = emptyMap(),
    val isExternal: Boolean = false
)

/**
 * Combined result returned by [MediaProvider.getStreams].
 */
@Serializable
data class StreamResult(
    val streams: List<StreamSource> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList()
)
