package com.atrishub.atrisstream.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atrishub.atrisstream.R
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.ui.theme.AtrisColors
import com.atrishub.atrisstream.ui.theme.TvFilterChip
import com.atrishub.atrisstream.ui.theme.TvFocusButton

private val qualityOptions = listOf("Auto", "2160p", "1080p", "720p", "480p")
private val subtitleLangOptions = listOf("tr", "en", "off")
private val fontSizes = listOf(16f, 20f, 24f, 28f, 32f)
private val textColorOptions = listOf(
    "White" to 0xFFFFFFFFL,
    "Yellow" to 0xFFFFEB3BL,
    "Cyan" to 0xFF38BDF8L,
    "Green" to 0xFF4ADE80L
)
private val bgOpacityOptions = listOf(
    "0%" to 0x00000000L,
    "50%" to 0x80000000L,
    "80%" to 0xCC000000L,
    "100%" to 0xFF000000L
)

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val language by viewModel.appLanguage.collectAsState()
    val quality by viewModel.preferredQuality.collectAsState()
    val subtitleLang by viewModel.preferredSubtitleLang.collectAsState()
    val appearance by viewModel.subtitleAppearance.collectAsState()
    val enabledSources by viewModel.enabledSources.collectAsState()
    val primaryMovieSourceId by viewModel.primaryMovieSourceId.collectAsState()
    val primaryTvSourceId by viewModel.primaryTvSourceId.collectAsState()
    val primaryAnimeSourceId by viewModel.primaryAnimeSourceId.collectAsState()
    val trackSeriesNewEpisodes by viewModel.trackSeriesNewEpisodes.collectAsState()

    val movieSources = enabledSources.filter { MediaType.MOVIE in it.supportedTypes }
    val tvSources = enabledSources.filter { MediaType.TV in it.supportedTypes }
    val animeSources = enabledSources.filter { MediaType.ANIME in it.supportedTypes }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AtrisColors.Background),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "header") {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // Primary Sources
        item(key = "primary_sources") {
            SettingsCard(title = stringResource(R.string.settings_primary_sources)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_primary_sources_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AtrisColors.TextSecondary
                    )

                    if (movieSources.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_primary_movies),
                            style = MaterialTheme.typography.labelLarge,
                            color = AtrisColors.TextSecondary
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(movieSources, key = { "movie_${it.id}" }) { source ->
                                TvFilterChip(
                                    label = source.name,
                                    selected = primaryMovieSourceId == source.id,
                                    onClick = { viewModel.setPrimaryMovieSourceId(source.id) }
                                )
                            }
                        }
                    }

                    if (tvSources.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_primary_series),
                            style = MaterialTheme.typography.labelLarge,
                            color = AtrisColors.TextSecondary
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(tvSources, key = { "tv_${it.id}" }) { source ->
                                TvFilterChip(
                                    label = source.name,
                                    selected = primaryTvSourceId == source.id,
                                    onClick = { viewModel.setPrimaryTvSourceId(source.id) }
                                )
                            }
                        }
                    }

                    if (animeSources.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_primary_anime),
                            style = MaterialTheme.typography.labelLarge,
                            color = AtrisColors.TextSecondary
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(animeSources, key = { "anime_${it.id}" }) { source ->
                                TvFilterChip(
                                    label = source.name,
                                    selected = primaryAnimeSourceId == source.id,
                                    onClick = { viewModel.setPrimaryAnimeSourceId(source.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // New Episode Tracking
        item(key = "episode_tracking") {
            SettingsCard(title = stringResource(R.string.settings_new_episode_tracking)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_new_episode_tracking_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AtrisColors.TextSecondary
                    )
                    Text(
                        text = stringResource(R.string.settings_track_series_toggle),
                        style = MaterialTheme.typography.labelLarge,
                        color = AtrisColors.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvFilterChip(
                            label = stringResource(R.string.settings_toggle_on),
                            selected = trackSeriesNewEpisodes,
                            onClick = { viewModel.setTrackSeriesNewEpisodes(true) }
                        )
                        TvFilterChip(
                            label = stringResource(R.string.settings_toggle_off),
                            selected = !trackSeriesNewEpisodes,
                            onClick = { viewModel.setTrackSeriesNewEpisodes(false) }
                        )
                    }
                }
            }
        }

        // Language
        item(key = "language") {
            SettingsCard(title = stringResource(R.string.settings_language)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvFilterChip(
                        label = stringResource(R.string.settings_lang_system),
                        selected = language == "system",
                        onClick = { viewModel.setAppLanguage("system") }
                    )
                    TvFilterChip(
                        label = stringResource(R.string.settings_lang_en),
                        selected = language == "en",
                        onClick = { viewModel.setAppLanguage("en") }
                    )
                    TvFilterChip(
                        label = stringResource(R.string.settings_lang_tr),
                        selected = language == "tr",
                        onClick = { viewModel.setAppLanguage("tr") }
                    )
                }
            }
        }

        // Preferred Quality
        item(key = "quality") {
            SettingsCard(title = stringResource(R.string.settings_quality)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    qualityOptions.forEach { option ->
                        TvFilterChip(
                            label = option,
                            selected = quality == option,
                            onClick = { viewModel.setPreferredQuality(option) }
                        )
                    }
                }
            }
        }

        // Preferred Subtitle Language
        item(key = "sub_lang") {
            SettingsCard(title = stringResource(R.string.settings_subtitle_lang)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    subtitleLangOptions.forEach { code ->
                        val label = when (code) {
                            "tr" -> "Türkçe (tr)"
                            "en" -> "English (en)"
                            else -> stringResource(R.string.settings_subtitle_off)
                        }
                        TvFilterChip(
                            label = label,
                            selected = subtitleLang == code,
                            onClick = { viewModel.setPreferredSubtitleLang(code) }
                        )
                    }
                }
            }
        }

        // Subtitle Appearance + Live Preview
        item(key = "sub_style") {
            SettingsCard(title = stringResource(R.string.settings_subtitle_style)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Live preview box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF06080C))
                            .border(1.dp, AtrisColors.CardBorder, RoundedCornerShape(10.dp))
                            .padding(vertical = 22.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(appearance.backgroundColorArgb.toInt()))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_subtitle_preview),
                                color = Color(appearance.textColorArgb.toInt()),
                                fontSize = appearance.fontSizeSp.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.settings_subtitle_size, appearance.fontSizeSp.toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        color = AtrisColors.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        fontSizes.forEach { size ->
                            TvFilterChip(
                                label = "${size.toInt()}sp",
                                selected = appearance.fontSizeSp == size,
                                onClick = { viewModel.updateSubtitleAppearance(appearance.copy(fontSizeSp = size)) }
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.settings_subtitle_color),
                        style = MaterialTheme.typography.labelLarge,
                        color = AtrisColors.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        textColorOptions.forEach { (label, argb) ->
                            TvFilterChip(
                                label = label,
                                selected = appearance.textColorArgb == argb,
                                onClick = { viewModel.updateSubtitleAppearance(appearance.copy(textColorArgb = argb)) }
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.settings_subtitle_bg),
                        style = MaterialTheme.typography.labelLarge,
                        color = AtrisColors.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        bgOpacityOptions.forEach { (label, argb) ->
                            TvFilterChip(
                                label = label,
                                selected = appearance.backgroundColorArgb == argb,
                                onClick = { viewModel.updateSubtitleAppearance(appearance.copy(backgroundColorArgb = argb)) }
                            )
                        }
                    }
                }
            }
        }

        // About AtrisStream & atrishub.com promotional card
        item(key = "about") {
            val siteUrl = stringResource(R.string.about_url)
            SettingsCard(title = stringResource(R.string.about_title)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.about_version),
                        style = MaterialTheme.typography.titleMedium,
                        color = AtrisColors.AccentCyan
                    )
                    Text(
                        text = stringResource(R.string.about_description),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AtrisColors.ElevatedCard)
                            .border(1.dp, AtrisColors.SecondaryIndigo.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "AtrisHub",
                                style = MaterialTheme.typography.titleMedium,
                                color = AtrisColors.TextPrimary
                            )
                            Text(
                                text = siteUrl,
                                color = AtrisColors.AccentCyan,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        TvFocusButton(
                            text = stringResource(R.string.about_visit_site),
                            icon = Icons.Default.OpenInBrowser,
                            isPrimary = true,
                            onClick = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(siteUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
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
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge
        )
        content()
    }
}
