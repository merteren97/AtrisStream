package com.atrishub.atrisstream.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PlayerLaunchArgs
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.ui.theme.AtrisColors
import com.atrishub.atrisstream.ui.theme.TvErrorBanner
import com.atrishub.atrisstream.ui.theme.TvFilterChip
import com.atrishub.atrisstream.ui.theme.TvFocusButton
import com.atrishub.atrisstream.ui.theme.TvLoadingSkeletonRow
import com.atrishub.atrisstream.ui.theme.TvPosterCard
import com.atrishub.atrisstream.ui.theme.TvSectionRow

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDetails: (sourceId: String, mediaId: String, type: MediaType) -> Unit,
    onPlayDirect: (PlayerLaunchArgs) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()
    var selectedProgressForMenu by remember { mutableStateOf<WatchProgress?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AtrisColors.Background),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Source selector bar
        if (state.providers.isNotEmpty()) {
            item(key = "source_pills") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.home_active_source),
                        style = MaterialTheme.typography.labelLarge,
                        color = AtrisColors.TextSecondary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.providers, key = { it.manifest.id }) { provider ->
                            TvFilterChip(
                                label = provider.manifest.name,
                                selected = provider.manifest.id == state.selectedSourceId,
                                onClick = { viewModel.selectSource(provider.manifest.id) }
                            )
                        }
                    }
                }
            }
        }

        // Empty sources state
        if (!state.isLoading && state.providers.isEmpty()) {
            item(key = "no_sources") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_sources_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AtrisColors.TextSecondary
                    )
                }
            }
        }

        // Hero Spotlight Banner
        state.featuredItem?.let { featured ->
            item(key = "hero_banner") {
                HeroSpotlightBanner(
                    item = featured,
                    onPlay = {
                        onPlayDirect(
                            PlayerLaunchArgs(
                                sourceId = featured.sourceId,
                                mediaId = featured.id,
                                type = featured.type,
                                title = featured.title,
                                posterUrl = featured.posterUrl
                            )
                        )
                    },
                    onDetails = {
                        onOpenDetails(featured.sourceId, featured.id, featured.type)
                    },
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        // Continue Watching row
        if (continueWatching.isNotEmpty()) {
            item(key = "continue_watching") {
                ContinueWatchingRow(
                    items = continueWatching,
                    onClick = { progress ->
                        onPlayDirect(
                            PlayerLaunchArgs(
                                sourceId = progress.sourceId,
                                mediaId = progress.mediaId,
                                type = progress.type,
                                title = progress.title,
                                posterUrl = progress.posterUrl,
                                seasonNumber = progress.seasonNumber,
                                episodeNumber = progress.episodeNumber,
                                resumePositionMs = progress.positionMs
                            )
                        )
                    },
                    onLongClick = { progress ->
                        selectedProgressForMenu = progress
                    }
                )
            }
        }

        // Loading skeletons
        if (state.isLoading) {
            items(2) {
                TvLoadingSkeletonRow()
            }
        }

        // Error state
        state.error?.let { err ->
            item(key = "home_error") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TvErrorBanner(message = err, onRetry = { viewModel.retry() })
                }
            }
        }

        // Content rows from active provider
        if (!state.isLoading && state.error == null) {
            if (state.sections.isEmpty() && state.providers.isNotEmpty()) {
                item(key = "home_empty") {
                    Text(
                        text = stringResource(R.string.home_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AtrisColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                itemsIndexed(
                    items = state.sections,
                    key = { index, section -> "${index}_${section.title}" },
                    contentType = { _, _ -> "home_section" }
                ) { _, section ->
                    TvSectionRow(
                        title = section.title,
                        items = section.items,
                        onItemClick = { card ->
                            onOpenDetails(card.sourceId, card.id, card.type)
                        }
                    )
                }
            }
        }
    }

    selectedProgressForMenu?.let { progress ->
        ContinueWatchingActionDialog(
            progress = progress,
            onRemove = {
                viewModel.removeFromContinueWatching(progress)
                selectedProgressForMenu = null
            },
            onMarkWatched = {
                viewModel.markAsWatched(progress)
                selectedProgressForMenu = null
            },
            onDismiss = { selectedProgressForMenu = null }
        )
    }
}

@Composable
private fun ContinueWatchingActionDialog(
    progress: WatchProgress,
    onRemove: () -> Unit,
    onMarkWatched: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstButtonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstButtonFocus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 440.dp)
                .border(1.dp, AtrisColors.CardBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = AtrisColors.Surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = progress.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AtrisColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                TvFocusButton(
                    text = stringResource(R.string.action_remove_continue_watching),
                    icon = Icons.Default.Delete,
                    isPrimary = false,
                    onClick = onRemove,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(firstButtonFocus)
                )

                TvFocusButton(
                    text = stringResource(R.string.action_mark_watched),
                    icon = Icons.Default.Check,
                    isPrimary = true,
                    onClick = onMarkWatched,
                    modifier = Modifier.fillMaxWidth()
                )

                TvFocusButton(
                    text = stringResource(R.string.action_cancel),
                    isPrimary = false,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HeroSpotlightBanner(
    item: MediaCard,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AtrisColors.Surface)
            .border(1.dp, AtrisColors.CardBorder, RoundedCornerShape(18.dp))
    ) {
        val imageUrl = item.backdropUrl ?: item.posterUrl
        if (!imageUrl.isNullOrBlank()) {
            val imageRequest = remember(imageUrl) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(960, 540)
                    .precision(Precision.INEXACT)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Cinematic dark gradient scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            AtrisColors.Background.copy(alpha = 0.95f),
                            AtrisColors.Background.copy(alpha = 0.72f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AtrisColors.AccentCyan.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.home_featured),
                        color = AtrisColors.AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                item.year?.let {
                    Text(text = it.toString(), style = MaterialTheme.typography.labelLarge, color = AtrisColors.TextSecondary)
                }
                item.rating?.let {
                    Text(
                        text = "★ ${String.format("%.1f", it)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = AtrisColors.AccentCyan
                    )
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            item.subtitle?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvFocusButton(
                    text = stringResource(R.string.action_play),
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    onClick = onPlay
                )
                TvFocusButton(
                    text = stringResource(R.string.action_details),
                    icon = Icons.Default.Info,
                    isPrimary = false,
                    onClick = onDetails
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<WatchProgress>,
    onClick: (WatchProgress) -> Unit,
    onLongClick: (WatchProgress) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.home_continue_watching),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(
                items = items,
                key = { "${it.sourceId}_${it.type.name}_${it.mediaId}_${it.seasonNumber}_${it.episodeNumber}" },
                contentType = { "continue_watching_card" }
            ) { progress ->
                val fraction = if (progress.durationMs > 0L) {
                    progress.positionMs.toFloat() / progress.durationMs.toFloat()
                } else {
                    0f
                }
                val epBadge = remember(progress.seasonNumber, progress.episodeNumber) {
                    if (progress.seasonNumber != null && progress.episodeNumber != null) {
                        "S${progress.seasonNumber} E${progress.episodeNumber}"
                    } else {
                        null
                    }
                }
                val card = remember(progress, epBadge) {
                    MediaCard(
                        id = progress.mediaId,
                        sourceId = progress.sourceId,
                        title = progress.title,
                        posterUrl = progress.posterUrl,
                        type = progress.type,
                        subtitle = epBadge
                    )
                }
                TvPosterCard(
                    item = card,
                    onClick = { onClick(progress) },
                    onLongClick = { onLongClick(progress) },
                    progressFraction = fraction,
                    badgeText = epBadge
                )
            }
        }
    }
}
