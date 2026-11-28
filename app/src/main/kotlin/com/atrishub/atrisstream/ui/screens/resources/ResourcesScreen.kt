package com.atrishub.atrisstream.ui.screens.resources

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.sourceengine.SourceHealthResult
import com.atrishub.atrisstream.ui.theme.AtrisColors
import com.atrishub.atrisstream.ui.theme.TvFilterChip
import com.atrishub.atrisstream.ui.theme.TvFocusButton

private enum class ResourceCategoryFilter {
    ALL,
    MOVIE,
    TV,
    ANIME
}

@Composable
fun ResourcesScreen(viewModel: ResourcesViewModel) {
    val sources by viewModel.sources.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val isLanRunning by viewModel.isLanServerRunning.collectAsState()
    val lanUrl by viewModel.lanServerUrl.collectAsState()
    val lanStatus by viewModel.lanLastStatus.collectAsState()
    val lanPin by viewModel.lanPairingPin.collectAsState()
    val primaryMovieId by viewModel.primaryMovieSourceId.collectAsState()
    val primaryTvId by viewModel.primaryTvSourceId.collectAsState()
    val primaryAnimeId by viewModel.primaryAnimeSourceId.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(ResourceCategoryFilter.ALL) }

    val filteredSources = remember(sources, selectedFilter) {
        when (selectedFilter) {
            ResourceCategoryFilter.ALL -> sources
            ResourceCategoryFilter.MOVIE -> sources.filter { MediaType.MOVIE in it.supportedTypes }
            ResourceCategoryFilter.TV -> sources.filter { MediaType.TV in it.supportedTypes }
            ResourceCategoryFilter.ANIME -> sources.filter { MediaType.ANIME in it.supportedTypes }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AtrisColors.Background),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.resources_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = stringResource(R.string.resources_subtitle),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                TvFocusButton(
                    text = stringResource(R.string.resources_add_url),
                    icon = Icons.Default.Add,
                    isPrimary = true,
                    onClick = {
                        viewModel.clearAddMessages()
                        showAddDialog = true
                    }
                )
            }
        }

        // LAN Web Paste Server card
        item(key = "lan_server") {
            LanPasteCard(
                isRunning = isLanRunning,
                serverUrl = lanUrl,
                pairingPin = lanPin,
                lastStatus = lanStatus,
                onToggle = { viewModel.toggleLanServer() },
                onRegeneratePin = { viewModel.regenerateLanPin() }
            )
        }

        // Category filter chips
        item(key = "category_filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "filter_all") {
                    TvFilterChip(
                        label = stringResource(R.string.resources_filter_all),
                        selected = selectedFilter == ResourceCategoryFilter.ALL,
                        onClick = { selectedFilter = ResourceCategoryFilter.ALL }
                    )
                }
                item(key = "filter_movie") {
                    TvFilterChip(
                        label = stringResource(R.string.resources_filter_movies),
                        selected = selectedFilter == ResourceCategoryFilter.MOVIE,
                        onClick = { selectedFilter = ResourceCategoryFilter.MOVIE }
                    )
                }
                item(key = "filter_tv") {
                    TvFilterChip(
                        label = stringResource(R.string.resources_filter_series),
                        selected = selectedFilter == ResourceCategoryFilter.TV,
                        onClick = { selectedFilter = ResourceCategoryFilter.TV }
                    )
                }
                item(key = "filter_anime") {
                    TvFilterChip(
                        label = stringResource(R.string.resources_filter_anime),
                        selected = selectedFilter == ResourceCategoryFilter.ANIME,
                        onClick = { selectedFilter = ResourceCategoryFilter.ANIME }
                    )
                }
            }
        }

        // Installed sources list
        items(filteredSources, key = { it.id }) { source ->
            SourceItemCard(
                source = source,
                isPrimaryMovie = source.id == primaryMovieId && MediaType.MOVIE in source.supportedTypes,
                isPrimaryTv = source.id == primaryTvId && MediaType.TV in source.supportedTypes,
                isPrimaryAnime = source.id == primaryAnimeId && MediaType.ANIME in source.supportedTypes,
                isTesting = source.id in state.testingSourceIds,
                healthResult = state.healthResults[source.id],
                onToggleEnabled = { viewModel.setSourceEnabled(source.id, !source.enabled) },
                onTest = { viewModel.testSource(source.id) },
                onRemove = { viewModel.removeSource(source.id) }
            )
        }
    }

    if (showAddDialog) {
        AddSourceUrlDialog(
            isAdding = state.isAdding,
            error = state.addError,
            onDismiss = { showAddDialog = false },
            onSubmit = { url ->
                viewModel.addSourceUrl(url) {
                    showAddDialog = false
                }
            }
        )
    }
}

@Composable
private fun LanPasteCard(
    isRunning: Boolean,
    serverUrl: String?,
    pairingPin: String?,
    lastStatus: String?,
    onToggle: () -> Unit,
    onRegeneratePin: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AtrisColors.Surface)
            .border(1.dp, AtrisColors.CardBorder, RoundedCornerShape(14.dp))
            .padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (isRunning) AtrisColors.Success else AtrisColors.AccentCyan
                )
                Text(
                    text = stringResource(R.string.resources_lan_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = stringResource(R.string.resources_lan_desc),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (isRunning && serverUrl != null) {
                    stringResource(R.string.resources_lan_running, serverUrl)
                } else {
                    stringResource(R.string.resources_lan_stopped)
                },
                color = if (isRunning) AtrisColors.AccentCyan else AtrisColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (isRunning && pairingPin != null) {
                Text(
                    text = stringResource(R.string.resources_lan_pin, pairingPin),
                    color = AtrisColors.Success,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = stringResource(R.string.resources_lan_pin_hint),
                    color = AtrisColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
            lastStatus?.let { msg ->
                Text(
                    text = msg,
                    color = AtrisColors.SecondaryIndigo,
                    fontSize = 13.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isRunning) {
                TvFocusButton(
                    text = stringResource(R.string.resources_lan_new_pin),
                    icon = Icons.Default.Refresh,
                    onClick = onRegeneratePin
                )
            }
            TvFocusButton(
                text = stringResource(
                    if (isRunning) R.string.resources_lan_stop else R.string.resources_lan_start
                ),
                isPrimary = !isRunning,
                onClick = onToggle
            )
        }
    }
}

@Composable
private fun SourceItemCard(
    source: InstalledSource,
    isPrimaryMovie: Boolean,
    isPrimaryTv: Boolean,
    isPrimaryAnime: Boolean,
    isTesting: Boolean,
    healthResult: SourceHealthResult?,
    onToggleEnabled: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AtrisColors.Surface)
            .border(1.dp, AtrisColors.CardBorder, RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleLarge
                    )
                    BadgePill(
                        text = source.driverType.name,
                        color = AtrisColors.SecondaryIndigo
                    )
                    if (source.isBuiltIn) {
                        BadgePill(
                            text = stringResource(R.string.resources_builtin),
                            color = AtrisColors.AccentCyan
                        )
                    }
                    if (isPrimaryMovie) {
                        BadgePill(
                            text = stringResource(R.string.resources_primary_movie),
                            color = AtrisColors.Success
                        )
                    }
                    if (isPrimaryTv) {
                        BadgePill(
                            text = stringResource(R.string.resources_primary_tv),
                            color = AtrisColors.Success
                        )
                    }
                    if (isPrimaryAnime) {
                        BadgePill(
                            text = stringResource(R.string.resources_primary_anime),
                            color = AtrisColors.Success
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    source.supportedTypes.forEach { mediaType ->
                        BadgePill(
                            text = mediaType.name,
                            color = AtrisColors.TextSecondary
                        )
                    }
                }
                Text(
                    text = "${source.baseUrl} • v${source.version}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvFocusButton(
                    text = stringResource(
                        if (source.enabled) R.string.resources_enabled else R.string.resources_disabled
                    ),
                    icon = Icons.Default.CheckCircle,
                    isPrimary = source.enabled,
                    onClick = onToggleEnabled
                )
                TvFocusButton(
                    text = stringResource(
                        if (isTesting) R.string.resources_testing else R.string.resources_test
                    ),
                    icon = Icons.Default.Speed,
                    enabled = !isTesting,
                    onClick = onTest
                )
                if (!source.isBuiltIn) {
                    TvFocusButton(
                        text = stringResource(R.string.action_remove),
                        icon = Icons.Default.Delete,
                        onClick = onRemove
                    )
                }
            }
        }

        healthResult?.let { res ->
            val msg = if (res.ok) {
                stringResource(R.string.resources_test_ok, res.latencyMs, res.itemCount)
            } else {
                stringResource(R.string.resources_test_fail, res.error ?: "Unknown")
            }
            Text(
                text = msg,
                color = if (res.ok) AtrisColors.Success else AtrisColors.Error,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun BadgePill(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AddSourceUrlDialog(
    isAdding: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var urlInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AtrisColors.Surface)
                .border(1.dp, AtrisColors.CardBorder, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = AtrisColors.AccentCyan
                )
                Text(
                    text = stringResource(R.string.resources_add_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            }

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.resources_add_dialog_hint),
                        color = AtrisColors.TextSecondary
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AtrisColors.AccentCyan,
                    unfocusedBorderColor = AtrisColors.CardBorder,
                    focusedTextColor = AtrisColors.TextPrimary,
                    unfocusedTextColor = AtrisColors.TextPrimary,
                    focusedContainerColor = AtrisColors.Background,
                    unfocusedContainerColor = AtrisColors.Background
                ),
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Text(
                    text = it,
                    color = AtrisColors.Error,
                    fontSize = 13.sp
                )
            }

            if (isAdding) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = AtrisColors.AccentCyan)
                    Text(
                        text = stringResource(R.string.resources_adding),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TvFocusButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                TvFocusButton(
                    text = stringResource(R.string.action_add),
                    isPrimary = true,
                    enabled = !isAdding && urlInput.isNotBlank(),
                    onClick = { onSubmit(urlInput) }
                )
            }
        }
    }
}
