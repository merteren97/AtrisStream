package com.atrishub.atrisstream.ui.screens.search

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.ui.theme.AtrisColors
import com.atrishub.atrisstream.ui.theme.TvErrorBanner
import com.atrishub.atrisstream.ui.theme.TvFilterChip
import com.atrishub.atrisstream.ui.theme.TvFocusButton
import com.atrishub.atrisstream.ui.theme.TvPosterCard

private val quickTags = listOf("Action", "Sci-Fi", "Anime", "Drama", "Comedy", "Thriller", "2024")

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenDetails: (sourceId: String, mediaId: String, type: MediaType) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtrisColors.Background)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onQueryChanged(it) },
                placeholder = { Text(stringResource(R.string.search_hint), color = AtrisColors.TextSecondary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AtrisColors.AccentCyan,
                    unfocusedBorderColor = AtrisColors.CardBorder,
                    focusedTextColor = AtrisColors.TextPrimary,
                    unfocusedTextColor = AtrisColors.TextPrimary,
                    focusedContainerColor = AtrisColors.Surface,
                    unfocusedContainerColor = AtrisColors.Surface
                ),
                modifier = Modifier.weight(1f)
            )

            TvFocusButton(
                text = stringResource(R.string.search_button),
                icon = Icons.Default.Search,
                isPrimary = true,
                onClick = { viewModel.search() }
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(quickTags) { tag ->
                TvFilterChip(
                    label = tag,
                    selected = state.query.equals(tag, ignoreCase = true),
                    onClick = { viewModel.search(tag) }
                )
            }
        }

        when {
            state.isSearching -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AtrisColors.AccentCyan)
                }
            }
            state.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TvErrorBanner(message = state.error!!, onRetry = { viewModel.search() })
                }
            }
            !state.hasSearched -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.search_empty_prompt),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AtrisColors.TextSecondary
                    )
                }
            }
            state.groups.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.search_no_results, state.query),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AtrisColors.TextSecondary
                    )
                }
            }
            else -> {
                val allItems = remember(state.groups) {
                    state.groups.flatMap { group ->
                        group.items.map { it to group.sourceName }
                    }.distinctBy { "${it.first.sourceId}_${it.first.type.name}_${it.first.id}" }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = allItems,
                        key = { "${it.first.sourceId}_${it.first.type.name}_${it.first.id}" },
                        contentType = { "search_card" }
                    ) { (card, sourceName) ->
                        TvPosterCard(
                            item = card,
                            badgeText = if (state.groups.size > 1) sourceName else null,
                            onClick = { onOpenDetails(card.sourceId, card.id, card.type) }
                        )
                    }
                }
            }
        }
    }
}
