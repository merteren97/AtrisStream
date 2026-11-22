package com.atrishub.atrisstream.feature.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atrishub.atrisstream.core.model.PlayerLaunchArgs
import com.atrishub.atrisstream.core.model.StreamSource
import com.atrishub.atrisstream.core.model.SubtitleAppearance

private val AccentColor = Color(0xFF7C4DFF)
private val PanelBackground = Color(0xE612131A)
private val PillBackground = Color(0x99242634)
private val FocusedBackground = Color(0xFFFFFFFF)

/**
 * TV D-pad focusable icon/text button with crisp focus highlight.
 */
@Composable
fun TvPlayerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    label: String? = null,
    isHighlighted: Boolean = false,
    onFocused: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    val bgColor = when {
        isFocused -> FocusedBackground
        isHighlighted -> AccentColor.copy(alpha = 0.35f)
        else -> PillBackground
    }
    val contentColor = if (isFocused) Color.Black else Color.White

    Row(
        modifier = modifier
            .scale(if (isFocused) 1.06f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        }
        if (label != null) {
            Text(
                text = label,
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Top bar showing Back button, title, season/episode badge, and active server/quality pill.
 */
@Composable
fun PlayerTopBar(
    launchArgs: PlayerLaunchArgs,
    currentStream: StreamSource?,
    selectedQualityLabel: String,
    onBack: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                )
            )
            .padding(horizontal = 32.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            TvPlayerButton(
                onClick = onBack,
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                label = "Back",
                onFocused = onUserInteraction
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = launchArgs.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (launchArgs.seasonNumber != null && launchArgs.episodeNumber != null) {
                    Text(
                        text = "S%02d • E%02d".format(launchArgs.seasonNumber, launchArgs.episodeNumber),
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (currentStream != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(PillBackground)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${currentStream.serverName} • $selectedQualityLabel",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Custom TV D-pad focusable seek bar with buffered indicator and elapsed/total time labels.
 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    onSeekBy: (Long) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onUserInteraction()
                }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key.nativeKeyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                onUserInteraction()
                                onSeekBy(-10_000L)
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onUserInteraction()
                                onSeekBy(10_000L)
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Track background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isFocused) 8.dp else 5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                // Buffered bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(buffered)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.45f))
                )
                // Played bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(if (isFocused) Color.White else AccentColor)
                )
            }

            // Focus thumb indicator
            if (isFocused) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (progress > 0.01f) {
                        Spacer(modifier = Modifier.weight(progress.coerceAtMost(0.99f)))
                    }
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.dp, AccentColor, CircleShape)
                    )
                    if (progress < 0.99f) {
                        Spacer(modifier = Modifier.weight((1f - progress).coerceAtLeast(0.01f)))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = PlayerMediaBuilder.formatTimeMs(positionMs),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = PlayerMediaBuilder.formatTimeMs(durationMs),
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Bottom HUD bar with seek bar, transport controls, and quick-action drawer buttons.
 */
@Composable
fun PlayerBottomBar(
    playerState: AtrisPlayerState,
    hasNextEpisode: Boolean,
    activeDrawer: PlayerDrawerType,
    playPauseFocusRequester: FocusRequester,
    onOpenDrawer: (PlayerDrawerType) -> Unit,
    onNextEpisode: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                )
            )
            .padding(horizontal = 32.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PlayerSeekBar(
            positionMs = playerState.currentPositionMs,
            durationMs = playerState.durationMs,
            bufferedPositionMs = playerState.bufferedPositionMs,
            onSeekBy = { delta -> playerState.seekBy(delta) },
            onUserInteraction = onUserInteraction
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Transport controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvPlayerButton(
                    onClick = {
                        onUserInteraction()
                        playerState.seekBy(-10_000L)
                    },
                    icon = Icons.Rounded.Replay10,
                    onFocused = onUserInteraction
                )
                TvPlayerButton(
                    onClick = {
                        onUserInteraction()
                        playerState.togglePlayPause()
                    },
                    icon = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    label = if (playerState.isPlaying) "Pause" else "Play",
                    isHighlighted = true,
                    onFocused = onUserInteraction,
                    modifier = Modifier.focusRequester(playPauseFocusRequester)
                )
                TvPlayerButton(
                    onClick = {
                        onUserInteraction()
                        playerState.seekBy(10_000L)
                    },
                    icon = Icons.Rounded.Forward10,
                    onFocused = onUserInteraction
                )
                if (hasNextEpisode) {
                    TvPlayerButton(
                        onClick = {
                            onUserInteraction()
                            onNextEpisode()
                        },
                        icon = Icons.Rounded.SkipNext,
                        label = "Next",
                        onFocused = onUserInteraction
                    )
                }
            }

            // Quick settings drawers
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvPlayerButton(
                    onClick = { onOpenDrawer(PlayerDrawerType.QUALITY) },
                    icon = Icons.Rounded.HighQuality,
                    label = "Quality",
                    isHighlighted = activeDrawer == PlayerDrawerType.QUALITY,
                    onFocused = onUserInteraction
                )
                TvPlayerButton(
                    onClick = { onOpenDrawer(PlayerDrawerType.SUBTITLES) },
                    icon = Icons.Rounded.Subtitles,
                    label = "Subtitles",
                    isHighlighted = activeDrawer == PlayerDrawerType.SUBTITLES ||
                        activeDrawer == PlayerDrawerType.SUBTITLE_STYLE ||
                        activeDrawer == PlayerDrawerType.ADD_SUBTITLE_URL,
                    onFocused = onUserInteraction
                )
                TvPlayerButton(
                    onClick = { onOpenDrawer(PlayerDrawerType.SERVERS) },
                    icon = Icons.Rounded.Dns,
                    label = "Servers",
                    isHighlighted = activeDrawer == PlayerDrawerType.SERVERS,
                    onFocused = onUserInteraction
                )
                TvPlayerButton(
                    onClick = { onOpenDrawer(PlayerDrawerType.AUDIO) },
                    icon = Icons.Rounded.Audiotrack,
                    label = "Audio",
                    isHighlighted = activeDrawer == PlayerDrawerType.AUDIO,
                    onFocused = onUserInteraction
                )
                TvPlayerButton(
                    onClick = { onOpenDrawer(PlayerDrawerType.SPEED) },
                    icon = Icons.Rounded.Speed,
                    label = "${playerState.playbackSpeed}x",
                    isHighlighted = activeDrawer == PlayerDrawerType.SPEED,
                    onFocused = onUserInteraction
                )
            }
        }
    }
}

/**
 * Visual seek indicator shown briefly when seeking via D-pad while HUD is hidden.
 */
@Composable
fun SeekFeedbackOverlay(
    seekDeltaMs: Long?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = seekDeltaMs != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val delta = seekDeltaMs ?: 0L
        val isForward = delta >= 0L
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.78f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isForward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                contentDescription = null,
                tint = Color.White
            )
            Text(
                text = if (isForward) "+${delta / 1000}s" else "${delta / 1000}s",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Right-side modal drawer for Quality, Subtitles, Subtitle Style, External Subtitle URL,
 * Servers, Audio, and Playback Speed.
 */
@Composable
fun PlayerSideDrawer(
    drawerType: PlayerDrawerType,
    playerState: AtrisPlayerState,
    subtitleAppearance: SubtitleAppearance,
    onUpdateSubtitleAppearance: (SubtitleAppearance) -> Unit,
    onChangeDrawer: (PlayerDrawerType) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(drawerType) {
        if (drawerType != PlayerDrawerType.NONE && drawerType != PlayerDrawerType.ADD_SUBTITLE_URL) {
            runCatching { firstItemFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(PanelBackground)
            .border(1.dp, Color.White.copy(alpha = 0.14f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Drawer header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val title = when (drawerType) {
                PlayerDrawerType.QUALITY -> "Video Quality"
                PlayerDrawerType.SUBTITLES -> "Subtitles"
                PlayerDrawerType.SUBTITLE_STYLE -> "Subtitle Style"
                PlayerDrawerType.ADD_SUBTITLE_URL -> "Add Subtitle URL"
                PlayerDrawerType.SERVERS -> "Select Server"
                PlayerDrawerType.AUDIO -> "Audio Track"
                PlayerDrawerType.SPEED -> "Playback Speed"
                PlayerDrawerType.NONE -> ""
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            TvPlayerButton(
                onClick = onClose,
                icon = Icons.Rounded.Close
            )
        }

        when (drawerType) {
            PlayerDrawerType.QUALITY -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(playerState.qualityOptions) { index, option ->
                        DrawerOptionRow(
                            label = option.label,
                            subtitle = if (!option.isAuto && option.bitrate > 0) {
                                "${option.bitrate / 1000} kbps"
                            } else null,
                            isSelected = option.id == playerState.selectedQualityId,
                            onClick = {
                                playerState.selectQuality(option)
                                onClose()
                            },
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier
                        )
                    }
                }
            }

            PlayerDrawerType.SUBTITLES -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvPlayerButton(
                        onClick = { onChangeDrawer(PlayerDrawerType.ADD_SUBTITLE_URL) },
                        icon = Icons.Rounded.Add,
                        label = "Add URL",
                        modifier = Modifier.weight(1f)
                    )
                    TvPlayerButton(
                        onClick = { onChangeDrawer(PlayerDrawerType.SUBTITLE_STYLE) },
                        icon = Icons.Rounded.Tune,
                        label = "Style",
                        modifier = Modifier.weight(1f)
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(playerState.textTracks) { index, track ->
                        DrawerOptionRow(
                            label = track.label,
                            subtitle = if (track.isExternal) "External" else track.language?.uppercase(),
                            isSelected = track.id == playerState.selectedTextTrackId,
                            onClick = {
                                playerState.selectTextTrack(track)
                                onClose()
                            },
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier
                        )
                    }
                }
            }

            PlayerDrawerType.ADD_SUBTITLE_URL -> {
                AddSubtitleUrlContent(
                    onAdd = { url, label ->
                        playerState.addExternalSubtitle(url = url, label = label)
                        onClose()
                    },
                    onCancel = { onChangeDrawer(PlayerDrawerType.SUBTITLES) }
                )
            }

            PlayerDrawerType.SUBTITLE_STYLE -> {
                SubtitleStyleContent(
                    appearance = subtitleAppearance,
                    onUpdate = onUpdateSubtitleAppearance,
                    firstFocusRequester = firstItemFocus
                )
            }

            PlayerDrawerType.SERVERS -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(playerState.streams) { index, stream ->
                        DrawerOptionRow(
                            label = stream.serverName,
                            subtitle = "${stream.quality} • ${stream.format.name}",
                            isSelected = index == playerState.currentStreamIndex,
                            onClick = {
                                playerState.switchServer(index)
                                onClose()
                            },
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier
                        )
                    }
                }
            }

            PlayerDrawerType.AUDIO -> {
                if (playerState.audioTracks.isEmpty()) {
                    Text(
                        text = "No alternate audio tracks available.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(playerState.audioTracks) { index, track ->
                            DrawerOptionRow(
                                label = track.label,
                                subtitle = track.language?.uppercase(),
                                isSelected = track.id == playerState.selectedAudioTrackId,
                                onClick = {
                                    playerState.selectAudioTrack(track)
                                    onClose()
                                },
                                modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier
                            )
                        }
                    }
                }
            }

            PlayerDrawerType.SPEED -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(PLAYBACK_SPEED_OPTIONS) { index, (speed, label) ->
                        DrawerOptionRow(
                            label = label,
                            subtitle = null,
                            isSelected = playerState.playbackSpeed == speed,
                            onClick = {
                                playerState.setSpeed(speed)
                                onClose()
                            },
                            modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier
                        )
                    }
                }
            }

            PlayerDrawerType.NONE -> Unit
        }
    }
}

@Composable
private fun DrawerOptionRow(
    label: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val bgColor = when {
        isFocused -> Color.White
        isSelected -> AccentColor.copy(alpha = 0.35f)
        else -> PillBackground
    }
    val textColor = if (isFocused) Color.Black else Color.White

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = textColor.copy(alpha = 0.72f),
                    fontSize = 12.sp
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = textColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AddSubtitleUrlContent(
    onAdd: (url: String, label: String) -> Unit,
    onCancel: () -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var labelText by remember { mutableStateOf("External") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color.White,
        unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
        focusedLabelColor = Color.White,
        unfocusedLabelColor = Color.White.copy(alpha = 0.7f)
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Subtitle URL (.srt / .vtt)") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = labelText,
            onValueChange = { labelText = it },
            label = { Text("Label") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { if (urlText.isNotBlank()) onAdd(urlText, labelText) },
                enabled = urlText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                modifier = Modifier.weight(1f)
            ) {
                Text("Add & Load")
            }
            TvPlayerButton(
                onClick = onCancel,
                label = "Cancel"
            )
        }
    }
}

@Composable
private fun SubtitleStyleContent(
    appearance: SubtitleAppearance,
    onUpdate: (SubtitleAppearance) -> Unit,
    firstFocusRequester: FocusRequester
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Live preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1F222E))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Sample Subtitle Preview",
                color = Color(appearance.textColorArgb),
                fontSize = appearance.fontSizeSp.sp,
                modifier = Modifier
                    .background(Color(appearance.backgroundColorArgb), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        // Font size controls
        Text("Font Size: ${appearance.fontSizeSp.toInt()}sp", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvPlayerButton(
                onClick = {
                    val next = (appearance.fontSizeSp - 2f).coerceAtLeast(14f)
                    onUpdate(appearance.copy(fontSizeSp = next))
                },
                label = "Smaller (-)",
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(firstFocusRequester)
            )
            TvPlayerButton(
                onClick = {
                    val next = (appearance.fontSizeSp + 2f).coerceAtMost(36f)
                    onUpdate(appearance.copy(fontSizeSp = next))
                },
                label = "Larger (+)",
                modifier = Modifier.weight(1f)
            )
        }

        // Background opacity presets
        Text("Background", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvPlayerButton(
                onClick = { onUpdate(appearance.copy(backgroundColorArgb = 0x00000000L)) },
                label = "None",
                isHighlighted = appearance.backgroundColorArgb == 0x00000000L,
                modifier = Modifier.weight(1f)
            )
            TvPlayerButton(
                onClick = { onUpdate(appearance.copy(backgroundColorArgb = 0x80000000L)) },
                label = "Semi",
                isHighlighted = appearance.backgroundColorArgb == 0x80000000L,
                modifier = Modifier.weight(1f)
            )
            TvPlayerButton(
                onClick = { onUpdate(appearance.copy(backgroundColorArgb = 0xE6000000L)) },
                label = "Solid",
                isHighlighted = appearance.backgroundColorArgb == 0xE6000000L,
                modifier = Modifier.weight(1f)
            )
        }

        // Text color presets
        Text("Text Color", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvPlayerButton(
                onClick = { onUpdate(appearance.copy(textColorArgb = 0xFFFFFFFFL)) },
                label = "White",
                isHighlighted = appearance.textColorArgb == 0xFFFFFFFFL,
                modifier = Modifier.weight(1f)
            )
            TvPlayerButton(
                onClick = { onUpdate(appearance.copy(textColorArgb = 0xFFFFEB3BL)) },
                label = "Yellow",
                isHighlighted = appearance.textColorArgb == 0xFFFFEB3BL,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
