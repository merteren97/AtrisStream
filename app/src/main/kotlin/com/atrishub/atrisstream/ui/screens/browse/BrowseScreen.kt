package com.atrishub.atrisstream.ui.screens.browse

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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.ui.theme.AtrisColors
import com.atrishub.atrisstream.ui.theme.TvErrorBanner
import com.atrishub.atrisstream.ui.theme.TvFilterChip
import com.atrishub.atrisstream.ui.theme.TvFocusButton
import com.atrishub.atrisstream.ui.theme.TvPosterCard

@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    onOpenDetails: (sourceId: String, mediaId: String, type: MediaType) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    val titleRes = when (state.mediaType) {
        MediaType.MOVIE -> R.string.browse_movies_title
        MediaType.TV -> R.string.browse_series_title
        MediaType.ANIME -> R.string.browse_anime_title
        MediaType.LIVE -> R.string.browse_movies_title
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 6
        }
    }

    LaunchedEffect(shouldLoadMore, state.hasNextPage, state.isLoadingMore) {
        if (shouldLoadMore && state.hasNextPage && !state.isLoadingMore && !state.isLoading) {
            viewModel.loadMore()
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineMedium
            )

            if (state.providers.isNotEmpty()) {
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

        when {
            state.providers.isEmpty() && !state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_sources_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AtrisColors.TextSecondary
                    )
                }
            }
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AtrisColors.AccentCyan)
                }
            }
            state.error != null && state.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TvErrorBanner(message = state.error!!, onRetry = { viewModel.retry() })
                }
            }
            state.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.browse_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AtrisColors.TextSecondary
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = state.items,
                        key = { "${it.sourceId}_${it.type.name}_${it.id}" },
                        contentType = { "media_card" }
                    ) { card ->
                        TvPosterCard(
                            item = card,
                            onClick = { onOpenDetails(card.sourceId, card.id, card.type) }
                        )
                    }

                    if (state.hasNextPage) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(color = AtrisColors.AccentCyan)
                                } else {
                                    TvFocusButton(
                                        text = stringResource(R.string.action_load_more),
                                        onClick = { viewModel.loadMore() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
