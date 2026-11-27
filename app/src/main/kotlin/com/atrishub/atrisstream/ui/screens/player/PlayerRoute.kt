package com.atrishub.atrisstream.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.atrishub.atrisstream.feature.player.AtrisPlayerScreen
import okhttp3.OkHttpClient

@Composable
fun PlayerRoute(
    viewModel: PlayerViewModel,
    okHttpClient: OkHttpClient,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val subtitleAppearance by viewModel.subtitleAppearance.collectAsState()
    val preferredQuality by viewModel.preferredQuality.collectAsState()
    val preferredSubtitleLang by viewModel.preferredSubtitleLang.collectAsState()

    AtrisPlayerScreen(
        launchArgs = state.launchArgs,
        streamResult = state.streamResult,
        isLoadingStreams = state.isLoading,
        streamError = state.error,
        okHttpClient = okHttpClient,
        subtitleAppearance = subtitleAppearance,
        preferredQuality = preferredQuality,
        preferredSubtitleLang = preferredSubtitleLang,
        hasNextEpisode = state.hasNextEpisode,
        onUpdateSubtitleAppearance = { viewModel.updateSubtitleAppearance(it) },
        onProgressUpdate = { pos, dur -> viewModel.saveProgress(pos, dur) },
        onPlaybackEnded = { pos, dur -> viewModel.saveProgress(pos, dur, isEnded = true) },
        onRetryLoadStreams = { viewModel.retry() },
        onNextEpisode = { viewModel.playNextEpisode() },
        onBack = onBack
    )
}
