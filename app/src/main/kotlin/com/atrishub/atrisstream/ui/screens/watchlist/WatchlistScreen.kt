package com.atrishub.atrisstream.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.ui.theme.AtrisColors
import com.atrishub.atrisstream.ui.theme.TvFilterChip
import com.atrishub.atrisstream.ui.theme.TvFocusButton
import com.atrishub.atrisstream.ui.theme.TvPosterCard

private enum class WatchlistFilter {
    ALL,
    NEW_EPISODE,
    ANIME,
    SERIES,
    MOVIES
}

@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel,
    onOpenDetails: (sourceId: String, mediaId: String, type: MediaType) -> Unit
) {
    val items by viewModel.items.collectAsState()
    val refreshState by viewModel.refreshState.collectAsState()
    var selectedFilter by remember { mutableStateOf(WatchlistFilter.ALL) }

    val filteredItems = remember(items, selectedFilter) {
        when (selectedFilter) {
            WatchlistFilter.ALL -> items
            WatchlistFilter.NEW_EPISODE -> items.filter { it.hasNewEpisode }
            WatchlistFilter.ANIME -> items.filter { it.type == MediaType.ANIME }
            WatchlistFilter.SERIES -> items.filter { it.type == MediaType.TV }
            WatchlistFilter.MOVIES -> items.filter { it.type == MediaType.MOVIE }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtrisColors.Background)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.watchlist_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                refreshState.lastNewEpisodesFound?.let { found ->
                    val statusText = if (found > 0) {
                        stringResource(R.string.watchlist_refresh_found, found)
                    } else {
                        stringResource(R.string.watchlist_refresh_up_to_date)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (found > 0) Color(0xFF10B981) else AtrisColors.TextSecondary
                    )
                }
            }

            TvFocusButton(
                text = stringResource(
                    if (refreshState.isRefreshing) R.string.watchlist_refreshing else R.string.watchlist_refresh
                ),
                icon = Icons.Default.Refresh,
                isPrimary = false,
                enabled = !refreshState.isRefreshing,
                onClick = { viewModel.refreshNewEpisodes() }
            )
        }

        if (items.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "wl_filter_all") {
                    TvFilterChip(
                        label = stringResource(R.string.watchlist_filter_all),
                        selected = selectedFilter == WatchlistFilter.ALL,
                        onClick = { selectedFilter = WatchlistFilter.ALL }
                    )
                }
                item(key = "wl_filter_new") {
                    TvFilterChip(
                        label = stringResource(R.string.watchlist_filter_new),
                        selected = selectedFilter == WatchlistFilter.NEW_EPISODE,
                        onClick = { selectedFilter = WatchlistFilter.NEW_EPISODE }
                    )
                }
                item(key = "wl_filter_anime") {
                    TvFilterChip(
                        label = stringResource(R.string.watchlist_filter_anime),
                        selected = selectedFilter == WatchlistFilter.ANIME,
                        onClick = { selectedFilter = WatchlistFilter.ANIME }
                    )
                }
                item(key = "wl_filter_series") {
                    TvFilterChip(
                        label = stringResource(R.string.watchlist_filter_series),
                        selected = selectedFilter == WatchlistFilter.SERIES,
                        onClick = { selectedFilter = WatchlistFilter.SERIES }
                    )
                }
                item(key = "wl_filter_movies") {
                    TvFilterChip(
                        label = stringResource(R.string.watchlist_filter_movies),
                        selected = selectedFilter == WatchlistFilter.MOVIES,
                        onClick = { selectedFilter = WatchlistFilter.MOVIES }
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.watchlist_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AtrisColors.TextSecondary
                )
            }
        } else if (filteredItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.watchlist_empty_filter),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AtrisColors.TextSecondary
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = filteredItems,
                    key = { "${it.sourceId}_${it.type.name}_${it.mediaId}" },
                    contentType = { "watchlist_card" }
                ) { item ->
                    val newBadge = if (item.hasNewEpisode) {
                        when {
                            item.latestAvailableSeason != null && item.latestAvailableEpisode != null ->
                                stringResource(
                                    R.string.watchlist_badge_new_se,
                                    item.latestAvailableSeason!!,
                                    item.latestAvailableEpisode!!
                                )
                            item.latestAvailableEpisode != null ->
                                stringResource(R.string.watchlist_badge_new_ep, item.latestAvailableEpisode!!)
                            else -> stringResource(R.string.watchlist_filter_new)
                        }
                    } else {
                        null
                    }
                    val watchedSubtitle = item.lastWatchedEpisode?.let { ep ->
                        stringResource(R.string.watchlist_watched_ep, ep)
                    } ?: item.type.name

                    val card = remember(item, watchedSubtitle) {
                        MediaCard(
                            id = item.mediaId,
                            sourceId = item.sourceId,
                            title = item.title,
                            posterUrl = item.posterUrl,
                            type = item.type,
                            year = item.year,
                            rating = item.rating,
                            subtitle = watchedSubtitle
                        )
                    }
                    TvPosterCard(
                        item = card,
                        onClick = { onOpenDetails(item.sourceId, item.mediaId, item.type) },
                        badgeText = newBadge,
                        badgeColor = if (item.hasNewEpisode) Color(0xFF10B981) else AtrisColors.AccentCyan,
                        highlightBorder = item.hasNewEpisode
                    )
                }
            }
        }
    }
}
