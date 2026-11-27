package com.atrishub.atrisstream.core.database

import com.atrishub.atrisstream.core.model.WatchProgressPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressCompletionTest {

    @Test
    fun `returns true immediately when playback ended`() {
        assertTrue(
            WatchProgressPolicy.isPlaybackCompleted(
                positionMs = 1_000L,
                durationMs = 7_200_000L,
                isEnded = true
            )
        )
        assertTrue(
            WatchProgressPolicy.isPlaybackCompleted(
                positionMs = 0L,
                durationMs = 0L,
                isEnded = true
            )
        )
    }

    @Test
    fun `returns false for invalid or zero position and duration`() {
        assertFalse(WatchProgressPolicy.isPlaybackCompleted(positionMs = 0L, durationMs = 1_320_000L))
        assertFalse(WatchProgressPolicy.isPlaybackCompleted(positionMs = -100L, durationMs = 1_320_000L))
        assertFalse(WatchProgressPolicy.isPlaybackCompleted(positionMs = 50_000L, durationMs = 0L))
        assertFalse(WatchProgressPolicy.isPlaybackCompleted(positionMs = 50_000L, durationMs = -1L))
    }

    @Test
    fun `22m anime uses short-form thresholds`() {
        val durationMs = 22 * 60_000L // 1,320,000 ms

        // 75% -> not completed
        assertFalse(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.75).toLong(), durationMs))

        // 85% (remaining = 198,000 ms > 150,000 ms) -> not completed
        assertFalse(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.85).toLong(), durationMs))

        // 88% -> completed
        assertTrue(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.88).toLong(), durationMs))

        // 120s remaining (fraction ~ 0.909 >= 0.80) -> completed
        assertTrue(WatchProgressPolicy.isPlaybackCompleted(durationMs - 120_000L, durationMs))
    }

    @Test
    fun `45m drama uses medium-form thresholds`() {
        val durationMs = 45 * 60_000L // 2,700,000 ms

        // 79% (remaining = 567,000 ms <= 600,000 ms, but fraction < 0.80) -> not completed
        assertFalse(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.79).toLong(), durationMs))

        // 80% with 540,000 ms remaining (<= 600,000 ms) -> completed
        assertTrue(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.80).toLong(), durationMs))

        // 90% -> completed
        assertTrue(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.90).toLong(), durationMs))
    }

    @Test
    fun `60m series uses medium-form thresholds`() {
        val durationMs = 60 * 60_000L // 3,600,000 ms

        // 82% -> remaining is 648,000 ms (> 600,000 ms) -> not completed
        assertFalse(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.82).toLong(), durationMs))

        // 9m remaining (540,000 ms <= 600,000 ms, fraction = 0.85 >= 0.80) -> completed
        assertTrue(WatchProgressPolicy.isPlaybackCompleted(durationMs - 540_000L, durationMs))

        // 90% -> completed
        assertTrue(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.90).toLong(), durationMs))
    }

    @Test
    fun `120m movie uses long-form thresholds`() {
        val durationMs = 120 * 60_000L // 7,200,000 ms

        // 84% -> not completed
        assertFalse(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.84).toLong(), durationMs))

        // 88% (remaining = 864,000 ms > 720,000 ms) -> not completed
        assertFalse(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.88).toLong(), durationMs))

        // 11m remaining (660,000 ms <= 720,000 ms, fraction ~ 0.908 >= 0.85) -> completed
        assertTrue(WatchProgressPolicy.isPlaybackCompleted(durationMs - 660_000L, durationMs))

        // 92% -> completed
        assertTrue(WatchProgressPolicy.isPlaybackCompleted((durationMs * 0.92).toLong(), durationMs))
    }
}
