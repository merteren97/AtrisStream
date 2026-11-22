package com.atrishub.atrisstream.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.atrishub.atrisstream.core.model.StreamFormat
import com.atrishub.atrisstream.core.model.StreamSource
import com.atrishub.atrisstream.core.model.SubtitleFormat
import com.atrishub.atrisstream.core.model.SubtitleTrack
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Helper functions for building Media3 [MediaItem]s, [MediaSource.Factory] instances,
 * and inspecting track metadata.
 */
object PlayerMediaBuilder {

    /**
     * Maps [SubtitleFormat] to its corresponding Media3 MIME type.
     */
    fun subtitleMimeType(format: SubtitleFormat): String = when (format) {
        SubtitleFormat.VTT -> MimeTypes.TEXT_VTT
        SubtitleFormat.SRT -> MimeTypes.APPLICATION_SUBRIP
        SubtitleFormat.ASS -> MimeTypes.TEXT_SSA
    }

    /**
     * Infers [SubtitleFormat] from a subtitle URL's file extension.
     */
    fun detectSubtitleFormat(url: String): SubtitleFormat {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            cleanPath.endsWith(".srt") -> SubtitleFormat.SRT
            cleanPath.endsWith(".ass") || cleanPath.endsWith(".ssa") -> SubtitleFormat.ASS
            else -> SubtitleFormat.VTT
        }
    }

    /**
     * Detects [StreamFormat] from [explicit] if known, or by inspecting the URL path.
     */
    fun detectStreamFormat(
        url: String,
        explicit: StreamFormat = StreamFormat.UNKNOWN
    ): StreamFormat {
        if (explicit != StreamFormat.UNKNOWN) return explicit
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            cleanPath.endsWith(".m3u8") || cleanPath.contains(".m3u8/") -> StreamFormat.HLS
            cleanPath.endsWith(".mpd") || cleanPath.contains(".mpd/") -> StreamFormat.DASH
            cleanPath.endsWith(".mp4") || cleanPath.endsWith(".mkv") || cleanPath.endsWith(".webm") -> StreamFormat.MP4
            else -> StreamFormat.UNKNOWN
        }
    }

    /**
     * Returns the Media3 MIME type for a stream, or null to let ExoPlayer auto-detect.
     */
    fun streamMimeType(format: StreamFormat, url: String): String? =
        when (detectStreamFormat(url, format)) {
            StreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
            StreamFormat.DASH -> MimeTypes.APPLICATION_MPD
            StreamFormat.MP4 -> MimeTypes.VIDEO_MP4
            StreamFormat.UNKNOWN -> null
        }

    /**
     * Builds an [OkHttpDataSource.Factory] configured with [headers] wrapped in a
     * [DefaultMediaSourceFactory] supporting HLS, DASH, and progressive MP4.
     */
    @OptIn(UnstableApi::class)
    fun buildMediaSourceFactory(
        okHttpClient: OkHttpClient,
        headers: Map<String, String>
    ): MediaSource.Factory {
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient as Call.Factory).apply {
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }
        return DefaultMediaSourceFactory(dataSourceFactory)
    }

    /**
     * Builds a [MediaItem] for [stream] with all [subtitles] attached as [MediaItem.SubtitleConfiguration].
     */
    fun buildMediaItem(
        stream: StreamSource,
        subtitles: List<SubtitleTrack>
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(stream.url))

        streamMimeType(stream.format, stream.url)?.let { mime ->
            builder.setMimeType(mime)
        }

        val subtitleConfigs = subtitles.map { track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(subtitleMimeType(track.format))
                .setLanguage(track.language)
                .setLabel(track.label)
                .setSelectionFlags(if (track.isExternal) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        if (subtitleConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs)
        }

        return builder.build()
    }

    /**
     * Extracts deduplicated, height-descending [VideoQualityOption]s from Media3 [Tracks],
     * prefixed by an "Auto" option.
     */
    fun buildQualityOptions(tracks: Tracks): List<VideoQualityOption> {
        val candidates = mutableListOf<VideoQualityCandidate>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (trackIndex in 0 until group.length) {
                    if (group.isTrackSupported(trackIndex)) {
                        val format = group.getTrackFormat(trackIndex)
                        if (format.height > 0) {
                            candidates.add(
                                VideoQualityCandidate(
                                    height = format.height,
                                    bitrate = format.bitrate,
                                    groupIndex = groupIndex,
                                    trackIndex = trackIndex
                                )
                            )
                        }
                    }
                }
            }
        }
        return buildQualityOptionsFromCandidates(candidates)
    }

    /**
     * Pure helper that deduplicates [candidates] by height (keeping highest bitrate),
     * sorts descending by height, and prepends the "Auto" option.
     */
    fun buildQualityOptionsFromCandidates(
        candidates: List<VideoQualityCandidate>
    ): List<VideoQualityOption> {
        val autoOption = VideoQualityOption(
            id = "auto",
            label = "Auto",
            height = Int.MAX_VALUE,
            bitrate = 0,
            isAuto = true
        )

        val deduped = candidates
            .filter { it.height > 0 }
            .groupBy { it.height }
            .mapNotNull { (height, group) ->
                val best = group.maxByOrNull { it.bitrate } ?: return@mapNotNull null
                VideoQualityOption(
                    id = "${height}p",
                    label = "${height}p",
                    height = height,
                    bitrate = best.bitrate,
                    groupIndex = best.groupIndex,
                    trackIndex = best.trackIndex,
                    isAuto = false
                )
            }
            .sortedByDescending { it.height }

        return listOf(autoOption) + deduped
    }

    /**
     * Extracts selectable [PlayerTextTrack]s from Media3 [Tracks], prefixed by an "Off" option.
     */
    fun buildTextTracks(tracks: Tracks): List<PlayerTextTrack> {
        val offOption = PlayerTextTrack(
            id = "off",
            label = "Off",
            language = null,
            isOff = true
        )
        val result = mutableListOf(offOption)
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }
                    val label = format.label?.takeIf { it.isNotBlank() }
                        ?: lang?.uppercase()
                        ?: "Subtitle ${result.size}"
                    result.add(
                        PlayerTextTrack(
                            id = "text_${groupIndex}_$trackIndex",
                            label = label,
                            language = lang,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            isOff = false,
                            isExternal = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
                        )
                    )
                }
            }
        }
        return result
    }

    /**
     * Extracts selectable [PlayerAudioTrack]s from Media3 [Tracks].
     */
    fun buildAudioTracks(tracks: Tracks): List<PlayerAudioTrack> {
        val result = mutableListOf<PlayerAudioTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (trackIndex in 0 until group.length) {
                    if (group.isTrackSupported(trackIndex)) {
                        val format = group.getTrackFormat(trackIndex)
                        val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }
                        val label = format.label?.takeIf { it.isNotBlank() }
                            ?: lang?.uppercase()
                            ?: "Audio ${result.size + 1}"
                        result.add(
                            PlayerAudioTrack(
                                id = "audio_${groupIndex}_$trackIndex",
                                label = label,
                                language = lang,
                                groupIndex = groupIndex,
                                trackIndex = trackIndex
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    /**
     * Formats milliseconds into `HH:MM:SS` (when >= 1 hour) or `MM:SS`.
     */
    fun formatTimeMs(timeMs: Long): String {
        val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
