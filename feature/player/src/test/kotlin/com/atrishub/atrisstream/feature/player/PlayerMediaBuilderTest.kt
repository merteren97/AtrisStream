package com.atrishub.atrisstream.feature.player

import androidx.media3.common.MimeTypes
import com.atrishub.atrisstream.core.model.StreamFormat
import com.atrishub.atrisstream.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMediaBuilderTest {

    @Test
    fun `subtitleMimeType maps VTT SRT and ASS accurately`() {
        assertEquals(MimeTypes.TEXT_VTT, PlayerMediaBuilder.subtitleMimeType(SubtitleFormat.VTT))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, PlayerMediaBuilder.subtitleMimeType(SubtitleFormat.SRT))
        assertEquals(MimeTypes.TEXT_SSA, PlayerMediaBuilder.subtitleMimeType(SubtitleFormat.ASS))
    }

    @Test
    fun `detectSubtitleFormat infers format ignoring query params`() {
        assertEquals(
            SubtitleFormat.SRT,
            PlayerMediaBuilder.detectSubtitleFormat("https://cdn.example.com/subs/movie.srt?token=123")
        )
        assertEquals(
            SubtitleFormat.ASS,
            PlayerMediaBuilder.detectSubtitleFormat("https://cdn.example.com/subs/anime.ASS#track1")
        )
        assertEquals(
            SubtitleFormat.ASS,
            PlayerMediaBuilder.detectSubtitleFormat("https://cdn.example.com/subs/anime.ssa")
        )
        assertEquals(
            SubtitleFormat.VTT,
            PlayerMediaBuilder.detectSubtitleFormat("https://cdn.example.com/subs/movie.vtt")
        )
    }

    @Test
    fun `detectStreamFormat identifies HLS DASH MP4 and respects explicit format`() {
        assertEquals(
            StreamFormat.HLS,
            PlayerMediaBuilder.detectStreamFormat("https://cdn.example.com/master.m3u8?hdnea=abc")
        )
        assertEquals(
            StreamFormat.DASH,
            PlayerMediaBuilder.detectStreamFormat("https://cdn.example.com/manifest.mpd")
        )
        assertEquals(
            StreamFormat.MP4,
            PlayerMediaBuilder.detectStreamFormat("https://cdn.example.com/video_1080.mp4?dl=1")
        )
        assertEquals(
            StreamFormat.UNKNOWN,
            PlayerMediaBuilder.detectStreamFormat("https://cdn.example.com/stream/12345")
        )
        assertEquals(
            StreamFormat.HLS,
            PlayerMediaBuilder.detectStreamFormat(
                url = "https://cdn.example.com/stream/12345",
                explicit = StreamFormat.HLS
            )
        )
    }

    @Test
    fun `formatTimeMs formats MM_SS and HH_MM_SS and clamps negatives`() {
        assertEquals("00:00", PlayerMediaBuilder.formatTimeMs(-500L))
        assertEquals("00:00", PlayerMediaBuilder.formatTimeMs(0L))
        assertEquals("01:05", PlayerMediaBuilder.formatTimeMs(65_000L))
        assertEquals("59:59", PlayerMediaBuilder.formatTimeMs(3_599_000L))
        assertEquals("01:00:00", PlayerMediaBuilder.formatTimeMs(3_600_000L))
        assertEquals("02:15:30", PlayerMediaBuilder.formatTimeMs((2 * 3600 + 15 * 60 + 30) * 1000L))
    }

    @Test
    fun `buildQualityOptionsFromCandidates deduplicates keeps highest bitrate and sorts descending`() {
        val candidates = listOf(
            VideoQualityCandidate(height = 480, bitrate = 800_000, groupIndex = 0, trackIndex = 0),
            VideoQualityCandidate(height = 1080, bitrate = 3_500_000, groupIndex = 0, trackIndex = 1),
            VideoQualityCandidate(height = 1080, bitrate = 5_000_000, groupIndex = 0, trackIndex = 2),
            VideoQualityCandidate(height = 720, bitrate = 2_000_000, groupIndex = 0, trackIndex = 3),
            VideoQualityCandidate(height = 2160, bitrate = 12_000_000, groupIndex = 0, trackIndex = 4),
            VideoQualityCandidate(height = -1, bitrate = 100_000, groupIndex = 0, trackIndex = 5)
        )

        val options = PlayerMediaBuilder.buildQualityOptionsFromCandidates(candidates)

        assertEquals(listOf("Auto", "2160p", "1080p", "720p", "480p"), options.map { it.label })
        assertTrue(options.first().isAuto)

        val opt1080 = options.first { it.label == "1080p" }
        assertEquals(5_000_000, opt1080.bitrate)
        assertEquals(2, opt1080.trackIndex)
    }
}
