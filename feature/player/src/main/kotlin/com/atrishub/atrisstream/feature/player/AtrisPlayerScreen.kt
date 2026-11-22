package com.atrishub.atrisstream.feature.player

import android.graphics.Color as AndroidColor
import android.util.TypedValue
import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.atrishub.atrisstream.core.model.PlayerLaunchArgs
import com.atrishub.atrisstream.core.model.StreamResult
import com.atrishub.atrisstream.core.model.SubtitleAppearance
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * Full-screen Android TV Media3 video player screen with custom D-pad HUD,
 * dynamic quality/subtitle/audio/server/speed drawers, external subtitle URL addition,
 * live subtitle appearance styling, and periodic watch-progress reporting.
 */
@OptIn(UnstableApi::class)
@Composable
fun AtrisPlayerScreen(
    launchArgs: PlayerLaunchArgs,
    streamResult: StreamResult?,
    isLoadingStreams: Boolean,
    streamError: String?,
    okHttpClient: OkHttpClient,
    subtitleAppearance: SubtitleAppearance,
    preferredQuality: String = "Auto",
    preferredSubtitleLang: String = "tr",
    hasNextEpisode: Boolean = false,
    onUpdateSubtitleAppearance: (SubtitleAppearance) -> Unit,
    onProgressUpdate: (positionMs: Long, durationMs: Long) -> Unit,
    onRetryLoadStreams: () -> Unit,
    onNextEpisode: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val playerState = remember(okHttpClient) {
        AtrisPlayerState(context = context, okHttpClient = okHttpClient)
    }

    val latestProgressCallback by rememberUpdatedState(onProgressUpdate)
    val latestOnBack by rememberUpdatedState(onBack)

    var isHudVisible by remember { mutableStateOf(true) }
    var activeDrawer by remember { mutableStateOf(PlayerDrawerType.NONE) }
    var lastInteractionTick by remember { mutableLongStateOf(0L) }
    var seekFeedbackDeltaMs by remember { mutableStateOf<Long?>(null) }

    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }

    fun wakeHud() {
        isHudVisible = true
        lastInteractionTick++
    }

    // Load streams when streamResult arrives or changes
    LaunchedEffect(streamResult) {
        if (streamResult != null) {
            playerState.loadStreamResult(
                streamResult = streamResult,
                resumePositionMs = launchArgs.resumePositionMs,
                preferredQuality = preferredQuality,
                preferredSubtitleLang = preferredSubtitleLang
            )
        }
    }

    // Auto-hide HUD after 5 seconds when playing and no drawer is open
    LaunchedEffect(isHudVisible, playerState.isPlaying, activeDrawer, lastInteractionTick) {
        if (isHudVisible && playerState.isPlaying && activeDrawer == PlayerDrawerType.NONE) {
            delay(5_000L)
            isHudVisible = false
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    // Request focus on Play/Pause when HUD opens without a drawer
    LaunchedEffect(isHudVisible, activeDrawer) {
        if (isHudVisible && activeDrawer == PlayerDrawerType.NONE) {
            delay(60L)
            runCatching { playPauseFocusRequester.requestFocus() }
        }
    }

    // Clear seek feedback pill after 1 second
    LaunchedEffect(seekFeedbackDeltaMs) {
        if (seekFeedbackDeltaMs != null) {
            delay(1_000L)
            seekFeedbackDeltaMs = null
        }
    }

    // Smooth UI position ticker + periodic 5s progress reporting
    LaunchedEffect(playerState) {
        var tickCount = 0
        while (true) {
            playerState.syncPositions()
            tickCount++
            if (tickCount % 10 == 0 && playerState.durationMs > 0L) {
                latestProgressCallback(playerState.currentPositionMs, playerState.durationMs)
            }
            delay(500L)
        }
    }

    // Report final progress & release ExoPlayer on dispose
    DisposableEffect(playerState) {
        onDispose {
            playerState.syncPositions()
            if (playerState.durationMs > 0L) {
                latestProgressCallback(playerState.currentPositionMs, playerState.durationMs)
            }
            playerState.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        playerState.togglePlayPause()
                        wakeHud()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                        playerState.player.play()
                        wakeHud()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        playerState.player.pause()
                        wakeHud()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!isHudVisible && activeDrawer == PlayerDrawerType.NONE) {
                            playerState.seekBy(-10_000L)
                            seekFeedbackDeltaMs = -10_000L
                            true
                        } else {
                            wakeHud()
                            false
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!isHudVisible && activeDrawer == PlayerDrawerType.NONE) {
                            playerState.seekBy(10_000L)
                            seekFeedbackDeltaMs = 10_000L
                            true
                        } else {
                            wakeHud()
                            false
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER -> {
                        if (!isHudVisible) {
                            wakeHud()
                            true
                        } else {
                            lastInteractionTick++
                            false
                        }
                    }
                    AndroidKeyEvent.KEYCODE_BACK -> {
                        when {
                            activeDrawer != PlayerDrawerType.NONE -> {
                                activeDrawer = PlayerDrawerType.NONE
                                wakeHud()
                                true
                            }
                            isHudVisible && playerState.isPlaying -> {
                                isHudVisible = false
                                runCatching { rootFocusRequester.requestFocus() }
                                true
                            }
                            else -> {
                                latestOnBack()
                                true
                            }
                        }
                    }
                    else -> {
                        wakeHud()
                        false
                    }
                }
            }
    ) {
        // Video surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerState.player
                    useController = false
                    keepScreenOn = true
                    setBackgroundColor(AndroidColor.BLACK)
                }
            },
            update = { playerView ->
                if (playerView.player !== playerState.player) {
                    playerView.player = playerState.player
                }
                playerView.subtitleView?.let { subView ->
                    subView.setStyle(
                        CaptionStyleCompat(
                            subtitleAppearance.textColorArgb.toInt(),
                            subtitleAppearance.backgroundColorArgb.toInt(),
                            AndroidColor.TRANSPARENT,
                            subtitleAppearance.edgeType,
                            AndroidColor.BLACK,
                            null
                        )
                    )
                    subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleAppearance.fontSizeSp)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering / stream loading spinner
        val activeError = streamError ?: playerState.playbackError
        if ((isLoadingStreams || playerState.isBuffering) && activeError == null) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Seek feedback indicator when HUD is hidden
        SeekFeedbackOverlay(
            seekDeltaMs = seekFeedbackDeltaMs,
            modifier = Modifier.align(Alignment.Center)
        )

        // Error card
        if (activeError != null) {
            PlayerErrorCard(
                message = activeError,
                onRetry = {
                    if (streamError != null) {
                        onRetryLoadStreams()
                    } else {
                        playerState.retryCurrentStream()
                    }
                },
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Top & bottom HUD overlays
        AnimatedVisibility(
            visible = isHudVisible && activeError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val selectedQualityLabel = playerState.qualityOptions
                    .firstOrNull { it.id == playerState.selectedQualityId }?.label ?: "Auto"

                PlayerTopBar(
                    launchArgs = launchArgs,
                    currentStream = playerState.currentStream,
                    selectedQualityLabel = selectedQualityLabel,
                    onBack = onBack,
                    onUserInteraction = { wakeHud() },
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                PlayerBottomBar(
                    playerState = playerState,
                    hasNextEpisode = hasNextEpisode,
                    activeDrawer = activeDrawer,
                    playPauseFocusRequester = playPauseFocusRequester,
                    onOpenDrawer = { drawer ->
                        activeDrawer = if (activeDrawer == drawer) PlayerDrawerType.NONE else drawer
                        wakeHud()
                    },
                    onNextEpisode = onNextEpisode,
                    onUserInteraction = { wakeHud() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // Settings side drawer
        AnimatedVisibility(
            visible = activeDrawer != PlayerDrawerType.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            PlayerSideDrawer(
                drawerType = activeDrawer,
                playerState = playerState,
                subtitleAppearance = subtitleAppearance,
                onUpdateSubtitleAppearance = onUpdateSubtitleAppearance,
                onChangeDrawer = {
                    activeDrawer = it
                    wakeHud()
                },
                onClose = {
                    activeDrawer = PlayerDrawerType.NONE
                    wakeHud()
                }
            )
        }
    }
}

@Composable
private fun PlayerErrorCard(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { retryFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE181A24))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Playback Error",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvPlayerButton(
                onClick = onRetry,
                label = "Retry",
                isHighlighted = true,
                modifier = Modifier.focusRequester(retryFocus)
            )
            TvPlayerButton(
                onClick = onBack,
                label = "Back"
            )
        }
    }
}
