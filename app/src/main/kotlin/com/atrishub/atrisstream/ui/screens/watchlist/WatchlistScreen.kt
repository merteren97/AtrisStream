package com.atrishub.atrisstream.ui.screens.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.ui.theme.AtrisColors
import com.atrishub.atrisstream.ui.theme.TvPosterCard

@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel,
    onOpenDetails: (sourceId: String, mediaId: String, type: MediaType) -> Unit
) {
    val items by viewModel.items.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtrisColors.Background)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.watchlist_title),
            style = MaterialTheme.typography.headlineMedium
        )

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.watchlist_empty),
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
                    items = items,
                    key = { "${it.sourceId}_${it.type.name}_${it.mediaId}" },
                    contentType = { "watchlist_card" }
                ) { item ->
                    val card = remember(item) {
                        MediaCard(
                            id = item.mediaId,
                            sourceId = item.sourceId,
                            title = item.title,
                            posterUrl = item.posterUrl,
                            type = item.type,
                            year = item.year,
                            rating = item.rating
                        )
                    }
                    TvPosterCard(
                        item = card,
                        onClick = { onOpenDetails(item.sourceId, item.mediaId, item.type) }
                    )
                }
            }
        }
    }
}
