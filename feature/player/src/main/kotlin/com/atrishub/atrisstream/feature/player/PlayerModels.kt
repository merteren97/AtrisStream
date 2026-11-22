package com.atrishub.atrisstream.feature.player

/**
 * Selectable video quality option derived from ExoPlayer's active video tracks.
 */
data class VideoQualityOption(
    val id: String,
    val label: String,
    val height: Int,
    val bitrate: Int,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
    val isAuto: Boolean = false
)

/**
 * Raw video track candidate before deduplication and sorting.
 */
data class VideoQualityCandidate(
    val height: Int,
    val bitrate: Int,
    val groupIndex: Int,
    val trackIndex: Int
)

/**
 * Selectable text/subtitle track derived from ExoPlayer's active text tracks.
 */
data class PlayerTextTrack(
    val id: String,
    val label: String,
    val language: String?,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
    val isOff: Boolean = false,
    val isExternal: Boolean = false
)

/**
 * Selectable audio track derived from ExoPlayer's active audio tracks.
 */
data class PlayerAudioTrack(
    val id: String,
    val label: String,
    val language: String?,
    val groupIndex: Int,
    val trackIndex: Int
)

/**
 * Active side-drawer panel in the player HUD.
 */
enum class PlayerDrawerType {
    NONE,
    QUALITY,
    SUBTITLES,
    SUBTITLE_STYLE,
    ADD_SUBTITLE_URL,
    SERVERS,
    AUDIO,
    SPEED
}

/**
 * Predefined playback speed options.
 */
val PLAYBACK_SPEED_OPTIONS: List<Pair<Float, String>> = listOf(
    0.75f to "0.75x",
    1.0f to "1.0x",
    1.25f to "1.25x",
    1.5f to "1.5x",
    2.0f to "2.0x"
)
