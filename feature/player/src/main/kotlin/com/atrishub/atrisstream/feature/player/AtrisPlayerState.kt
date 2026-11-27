package com.atrishub.atrisstream.feature.player

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.atrishub.atrisstream.core.model.StreamResult
import com.atrishub.atrisstream.core.model.StreamSource
import com.atrishub.atrisstream.core.model.SubtitleTrack
import okhttp3.OkHttpClient

/**
 * State holder and Media3 ExoPlayer controller for [AtrisPlayerScreen].
 *
 * Manages stream loading, server switching, dynamic video quality selection,
 * subtitle track selection + runtime external subtitle URL attachment,
 * audio track selection, and playback speed.
 */
@OptIn(UnstableApi::class)
@Stable
class AtrisPlayerState(
    context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val appContext = context.applicationContext
    private val isLowRam: Boolean =
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true

    private val autoMaxVideoWidth: Int = if (isLowRam) 1280 else 1920
    private val autoMaxVideoHeight: Int = if (isLowRam) 720 else 1080
    private val autoMaxVideoBitrate: Int = if (isLowRam) 4_000_000 else 8_000_000

    private val loadControl = DefaultLoadControl.Builder()
        .setAllocator(DefaultAllocator(true, 32 * 1024))
        .setBufferDurationsMs(15_000, 25_000, 2_000, 4_000)
        .setTargetBufferBytes(if (isLowRam) 16 * 1024 * 1024 else 24 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    private val trackSelector = DefaultTrackSelector(appContext).apply {
        setParameters(
            buildUponParameters()
                .setMaxVideoSize(autoMaxVideoWidth, autoMaxVideoHeight)
                .setMaxVideoBitrate(autoMaxVideoBitrate)
        )
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
        .build()

    var onPlaybackEnded: ((positionMs: Long, durationMs: Long) -> Unit)? = null

    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(true)
        private set
    var currentPositionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var bufferedPositionMs by mutableLongStateOf(0L)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    var streams by mutableStateOf<List<StreamSource>>(emptyList())
        private set
    var currentStreamIndex by mutableIntStateOf(0)
        private set
    var subtitles by mutableStateOf<List<SubtitleTrack>>(emptyList())
        private set

    var qualityOptions by mutableStateOf<List<VideoQualityOption>>(
        listOf(VideoQualityOption(id = "auto", label = "Auto", height = Int.MAX_VALUE, bitrate = 0, isAuto = true))
    )
        private set
    var selectedQualityId by mutableStateOf("auto")
        private set

    var textTracks by mutableStateOf<List<PlayerTextTrack>>(
        listOf(PlayerTextTrack(id = "off", label = "Off", language = null, isOff = true))
    )
        private set
    var selectedTextTrackId by mutableStateOf("off")
        private set

    var audioTracks by mutableStateOf<List<PlayerAudioTrack>>(emptyList())
        private set
    var selectedAudioTrackId by mutableStateOf("")
        private set

    var playbackSpeed by mutableFloatStateOf(1.0f)
        private set

    val currentStream: StreamSource?
        get() = streams.getOrNull(currentStreamIndex)

    private var preferredQuality: String = "Auto"
    private var preferredSubtitleLang: String = ""
    private var appliedInitialPreferences = false
    private var currentStreamResult: StreamResult? = null
    private var pausedPositionMs: Long = 0L
    private var wasStoppedInBackground: Boolean = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                playbackError = null
                syncPositions()
            } else if (playbackState == Player.STATE_ENDED) {
                syncPositions()
                val finalPos = if (durationMs > 0L) durationMs else currentPositionMs
                onPlaybackEnded?.invoke(finalPos, durationMs)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTrackLists(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            isBuffering = false
            playbackError = error.localizedMessage ?: "Playback failed (${error.errorCodeName})"
        }
    }

    init {
        player.addListener(listener)
    }

    /**
     * Loads a [StreamResult] starting at the first stream (or matching [preferredQuality])
     * and seeking to [resumePositionMs].
     */
    fun loadStreamResult(
        streamResult: StreamResult,
        resumePositionMs: Long = 0L,
        preferredQuality: String = "Auto",
        preferredSubtitleLang: String = "tr"
    ) {
        this.preferredQuality = preferredQuality
        this.preferredSubtitleLang = preferredSubtitleLang
        this.appliedInitialPreferences = false
        this.currentStreamResult = streamResult
        this.pausedPositionMs = 0L
        this.wasStoppedInBackground = false

        streams = streamResult.streams
        subtitles = streamResult.subtitles
        playbackError = null

        if (streams.isEmpty()) {
            playbackError = "No playable streams available."
            isBuffering = false
            return
        }

        val initialIndex = streams.indexOfFirst {
            it.quality.equals(preferredQuality, ignoreCase = true)
        }.takeIf { it >= 0 } ?: 0

        currentStreamIndex = initialIndex
        prepareStream(
            stream = streams[initialIndex],
            startPositionMs = resumePositionMs.coerceAtLeast(0L),
            playWhenReady = true
        )
    }

    /**
     * Switches to a different [StreamSource] server while preserving playback position and play state.
     */
    fun switchServer(index: Int) {
        if (index !in streams.indices || index == currentStreamIndex) return
        val resumePos = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady
        currentStreamIndex = index
        appliedInitialPreferences = false
        playbackError = null
        prepareStream(
            stream = streams[index],
            startPositionMs = resumePos,
            playWhenReady = shouldPlay
        )
    }

    /**
     * Retries preparing the current stream from the last known position.
     */
    fun retryCurrentStream() {
        val stream = currentStream ?: return
        val resumePos = player.currentPosition.coerceAtLeast(0L)
        playbackError = null
        prepareStream(stream = stream, startPositionMs = resumePos, playWhenReady = true)
    }

    /**
     * Appends a custom external subtitle URL at runtime and reloads the current [MediaItem]
     * while preserving exact [Player.getCurrentPosition] and [Player.getPlayWhenReady].
     */
    fun addExternalSubtitle(
        url: String,
        label: String = "External Subtitle",
        language: String = "und"
    ) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return
        val stream = currentStream ?: return

        val newTrack = SubtitleTrack(
            url = trimmedUrl,
            language = language.ifBlank { "und" },
            label = label.ifBlank { "External Subtitle" },
            format = PlayerMediaBuilder.detectSubtitleFormat(trimmedUrl),
            isExternal = true
        )
        subtitles = subtitles + newTrack

        val exactPosition = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady

        // Ensure text tracks are enabled so the newly added subtitle is active.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()

        prepareStream(
            stream = stream,
            startPositionMs = exactPosition,
            playWhenReady = shouldPlay
        )
    }

    /**
     * Selects a specific video quality or resets to Auto.
     */
    fun selectQuality(option: VideoQualityOption) {
        selectedQualityId = option.id
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)

        if (option.isAuto) {
            builder
                .setMaxVideoSize(autoMaxVideoWidth, autoMaxVideoHeight)
                .setMaxVideoBitrate(autoMaxVideoBitrate)
        } else {
            builder
                .clearVideoSizeConstraints()
                .setMaxVideoBitrate(Int.MAX_VALUE)
            val group = player.currentTracks.groups.getOrNull(option.groupIndex)
            if (group != null && option.trackIndex in 0 until group.length) {
                builder.addOverride(
                    TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex)
                )
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    /**
     * Selects a subtitle track or disables subtitles when [track.isOff] is true.
     */
    fun selectTextTrack(track: PlayerTextTrack) {
        selectedTextTrackId = track.id
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)

        if (track.isOff) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val group = player.currentTracks.groups.getOrNull(track.groupIndex)
            if (group != null && track.trackIndex in 0 until group.length) {
                builder.addOverride(
                    TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                )
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    /**
     * Selects an audio track.
     */
    fun selectAudioTrack(track: PlayerAudioTrack) {
        selectedAudioTrackId = track.id
        val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
        if (track.trackIndex !in 0 until group.length) return

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
            .build()
    }

    /**
     * Updates playback speed (e.g., 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).
     */
    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        player.playbackParameters = PlaybackParameters(speed)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = if (durationMs > 0L) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        player.seekTo(clamped)
        currentPositionMs = clamped
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
    }

    /**
     * Refreshes [currentPositionMs], [durationMs], and [bufferedPositionMs] from [player].
     */
    fun syncPositions() {
        currentPositionMs = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration
        durationMs = if (dur == C.TIME_UNSET || dur < 0L) 0L else dur
        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
    }

    /**
     * Stops and clears media items and resets playback position state (e.g. when switching episodes).
     */
    fun clearPlayback() {
        player.stop()
        player.clearMediaItems()
        currentStreamResult = null
        streams = emptyList()
        subtitles = emptyList()
        currentStreamIndex = 0
        currentPositionMs = 0L
        durationMs = 0L
        bufferedPositionMs = 0L
        playbackError = null
        isBuffering = false
        pausedPositionMs = 0L
        wasStoppedInBackground = false
    }

    /**
     * Saves the current position and stops the player when moving to background.
     */
    fun stopForBackground() {
        if (currentStreamResult != null && player.mediaItemCount > 0) {
            pausedPositionMs = player.currentPosition.coerceAtLeast(0L)
            wasStoppedInBackground = true
            player.stop()
        }
    }

    /**
     * Re-prepares the active stream paused at [pausedPositionMs] when returning from background.
     */
    fun resumeFromBackground() {
        if (wasStoppedInBackground) {
            wasStoppedInBackground = false
            val stream = currentStream ?: return
            prepareStream(
                stream = stream,
                startPositionMs = pausedPositionMs,
                playWhenReady = false
            )
        }
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }

    private fun prepareStream(
        stream: StreamSource,
        startPositionMs: Long,
        playWhenReady: Boolean
    ) {
        isBuffering = true
        val mediaSourceFactory = PlayerMediaBuilder.buildMediaSourceFactory(
            okHttpClient = okHttpClient,
            headers = stream.headers
        )
        val mediaItem = PlayerMediaBuilder.buildMediaItem(
            stream = stream,
            subtitles = subtitles
        )
        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
        player.setMediaSource(mediaSource, startPositionMs)
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    private fun updateTrackLists(tracks: Tracks) {
        qualityOptions = PlayerMediaBuilder.buildQualityOptions(tracks)
        textTracks = PlayerMediaBuilder.buildTextTracks(tracks)
        audioTracks = PlayerMediaBuilder.buildAudioTracks(tracks)

        // Sync selected quality if Auto or matching override
        val activeVideoOverride = player.trackSelectionParameters.overrides.values
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        if (activeVideoOverride == null) {
            selectedQualityId = "auto"
        }

        // Sync selected text track
        val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        if (textDisabled) {
            selectedTextTrackId = "off"
        } else {
            val selectedText = textTracks.firstOrNull { track ->
                !track.isOff && tracks.groups.getOrNull(track.groupIndex)?.isTrackSelected(track.trackIndex) == true
            }
            if (selectedText != null) {
                selectedTextTrackId = selectedText.id
            }
        }

        // Sync selected audio track
        val selectedAudio = audioTracks.firstOrNull { track ->
            tracks.groups.getOrNull(track.groupIndex)?.isTrackSelected(track.trackIndex) == true
        }
        if (selectedAudio != null) {
            selectedAudioTrackId = selectedAudio.id
        } else if (audioTracks.isNotEmpty() && selectedAudioTrackId.isEmpty()) {
            selectedAudioTrackId = audioTracks.first().id
        }

        // Apply preferred quality & subtitle language once per stream load
        if (!appliedInitialPreferences && tracks.groups.isNotEmpty()) {
            appliedInitialPreferences = true
            if (!preferredQuality.equals("Auto", ignoreCase = true)) {
                qualityOptions.firstOrNull {
                    it.label.equals(preferredQuality, ignoreCase = true)
                }?.let { selectQuality(it) }
            }
            if (preferredSubtitleLang.isNotBlank()) {
                val externalMatch = textTracks.firstOrNull { it.isExternal }
                val langMatch = textTracks.firstOrNull {
                    !it.isOff && it.language.equals(preferredSubtitleLang, ignoreCase = true)
                }
                (externalMatch ?: langMatch)?.let { selectTextTrack(it) }
            }
        }
    }
}
