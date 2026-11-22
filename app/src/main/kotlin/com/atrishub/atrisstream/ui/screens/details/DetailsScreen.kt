package com.atrishub.atrisstream.ui.screens.details

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.Episode
import com.atrishub.atrisstream.core.model.MediaDetails
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PlayerLaunchArgs
import com.atrishub.atrisstream.ui.theme.AtrisColors
import com.atrishub.atrisstream.ui.theme.TvErrorBanner
import com.atrishub.atrisstream.ui.theme.TvFilterChip
import com.atrishub.atrisstream.ui.theme.TvFocusButton

@Composable
fun DetailsScreen(
    viewModel: DetailsViewModel,
    onPlay: (PlayerLaunchArgs) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val isSaved by viewModel.isSavedToWatchlist.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AtrisColors.Background)
    ) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    color = AtrisColors.AccentCyan,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            state.error != null -> {
                TvErrorBanner(
                    message = state.error!!,
                    onRetry = { viewModel.loadDetails() },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            state.details != null -> {
                val details = state.details!!
                DetailsContent(
                    details = details,
                    state = state,
                    isSaved = isSaved,
                    onToggleWatchlist = { viewModel.toggleWatchlist() },
                    onSelectSeason = { viewModel.selectSeason(it) },
                    onPlayMain = {
                        val firstEp = state.episodes.firstOrNull()
                        onPlay(
                            PlayerLaunchArgs(
                                sourceId = details.sourceId,
                                mediaId = details.id,
                                type = details.type,
                                title = details.title,
                                posterUrl = details.posterUrl,
                                seasonNumber = if (details.type == MediaType.MOVIE) null else (firstEp?.seasonNumber ?: state.selectedSeasonNumber),
                                episodeNumber = if (details.type == MediaType.MOVIE) null else (firstEp?.episodeNumber ?: 1),
                                resumePositionMs = state.watchProgress?.positionMs ?: 0L
                            )
                        )
                    },
                    onPlayEpisode = { episode ->
                        onPlay(
                            PlayerLaunchArgs(
                                sourceId = details.sourceId,
                                mediaId = details.id,
                                type = details.type,
                                title = "${details.title} • S${episode.seasonNumber}E${episode.episodeNumber}",
                                posterUrl = episode.thumbnailUrl ?: details.posterUrl,
                                seasonNumber = episode.seasonNumber,
                                episodeNumber = episode.episodeNumber,
                                resumePositionMs = 0L
                            )
                        )
                    },
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
private fun DetailsContent(
    details: MediaDetails,
    state: DetailsUiState,
    isSaved: Boolean,
    onToggleWatchlist: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onPlayMain: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-bleed backdrop
        val backdrop = details.backdropUrl ?: details.posterUrl
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = details.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
            )
        }

        // Gradient scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            AtrisColors.Background.copy(alpha = 0.45f),
                            AtrisColors.Background.copy(alpha = 0.92f),
                            AtrisColors.Background
                        )
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item(key = "top_back") {
                TvFocusButton(
                    text = stringResource(R.string.action_back),
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onBack
                )
            }

            item(key = "hero_info") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    if (!details.posterUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = details.posterUrl,
                            contentDescription = details.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(170.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, AtrisColors.CardBorder, RoundedCornerShape(14.dp))
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = details.title,
                            style = MaterialTheme.typography.headlineLarge
                        )

                        // Metadata pills
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            details.year?.let {
                                Text(text = it.toString(), style = MaterialTheme.typography.labelLarge, color = AtrisColors.TextSecondary)
                            }
                            details.rating?.let {
                                Text(
                                    text = "★ ${String.format("%.1f", it)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AtrisColors.AccentCyan
                                )
                            }
                            details.runtimeMinutes?.let {
                                Text(
                                    text = stringResource(R.string.details_runtime_min, it),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AtrisColors.TextSecondary
                                )
                            }
                            Text(
                                text = details.type.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = AtrisColors.SecondaryIndigo
                            )
                        }

                        if (details.genres.isNotEmpty()) {
                            Text(
                                text = details.genres.joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AtrisColors.AccentCyan.copy(alpha = 0.9f)
                            )
                        }

                        details.description?.takeIf { it.isNotBlank() }?.let { overview ->
                            Text(
                                text = overview,
                                style = MaterialTheme.typography.bodyLarge,
                                color = AtrisColors.TextPrimary.copy(alpha = 0.9f),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (details.cast.isNotEmpty()) {
                            Text(
                                text = "${stringResource(R.string.details_cast)}: ${details.cast.take(8).joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val hasResume = (state.watchProgress?.positionMs ?: 0L) > 5_000L
                            TvFocusButton(
                                text = stringResource(if (hasResume) R.string.action_resume else R.string.action_play),
                                icon = Icons.Default.PlayArrow,
                                isPrimary = true,
                                onClick = onPlayMain
                            )
                            TvFocusButton(
                                text = stringResource(
                                    if (isSaved) R.string.details_remove_watchlist else R.string.details_add_watchlist
                                ),
                                icon = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                isPrimary = false,
                                onClick = onToggleWatchlist
                            )
                        }
                    }
                }
            }

            // Seasons & Episodes (for TV / ANIME)
            if (details.seasons.isNotEmpty()) {
                item(key = "seasons_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.details_seasons),
                            style = MaterialTheme.typography.titleLarge
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(details.seasons, key = { it.seasonNumber }) { season ->
                                TvFilterChip(
                                    label = season.name.ifBlank {
                                        stringResource(R.string.details_season_label, season.seasonNumber)
                                    },
                                    selected = season.seasonNumber == state.selectedSeasonNumber,
                                    onClick = { onSelectSeason(season.seasonNumber) }
                                )
                            }
                        }
                    }
                }

                item(key = "episodes_title") {
                    Text(
                        text = stringResource(R.string.details_episodes),
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                if (state.isLoadingEpisodes) {
                    item(key = "episodes_loading") {
                        CircularProgressIndicator(color = AtrisColors.AccentCyan)
                    }
                } else {
                    items(
                        items = state.episodes,
                        key = { "${it.seasonNumber}_${it.episodeNumber}_${it.id}" }
                    ) { episode ->
                        EpisodeRowCard(
                            episode = episode,
                            onClick = { onPlayEpisode(episode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRowCard(
    episode: Episode,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = if (isFocused) AtrisColors.ElevatedCard else AtrisColors.Surface,
        label = "epBg"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) AtrisColors.AccentCyan else AtrisColors.CardBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AtrisColors.ElevatedCard),
                contentAlignment = Alignment.Center
            ) {
                if (!episode.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.thumbnailUrl,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isFocused) AtrisColors.AccentCyan else AtrisColors.TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.details_episode_label, episode.episodeNumber, episode.title),
                    color = if (isFocused) AtrisColors.AccentCyan else AtrisColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                episode.overview?.takeIf { it.isNotBlank() }?.let { ov ->
                    Text(
                        text = ov,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            episode.runtimeMinutes?.let { min ->
                Text(
                    text = stringResource(R.string.details_runtime_min, min),
                    style = MaterialTheme.typography.labelLarge,
                    color = AtrisColors.TextSecondary
                )
            }
        }
    }
}
