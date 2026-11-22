package com.atrishub.atrisstream.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.database.preferences.UserPreferencesRepository
import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.model.Episode
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PlayerLaunchArgs
import com.atrishub.atrisstream.core.model.StreamResult
import com.atrishub.atrisstream.core.model.SubtitleAppearance
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val launchArgs: PlayerLaunchArgs,
    val streamResult: StreamResult? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val hasNextEpisode: Boolean = false
)

class PlayerViewModel(
    initialArgs: PlayerLaunchArgs,
    private val sourceManager: SourceManager,
    private val repository: AtrisRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState(launchArgs = initialArgs))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val subtitleAppearance: StateFlow<SubtitleAppearance> = preferencesRepository.subtitleAppearance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleAppearance())

    val preferredQuality: StateFlow<String> = preferencesRepository.preferredQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Auto")

    val preferredSubtitleLang: StateFlow<String> = preferencesRepository.preferredSubtitleLang
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "tr")

    private var seasonEpisodes: List<Episode> = emptyList()

    init {
        loadStreams(initialArgs)
    }

    fun retry() {
        loadStreams(_uiState.value.launchArgs)
    }

    fun updateSubtitleAppearance(appearance: SubtitleAppearance) {
        viewModelScope.launch {
            preferencesRepository.setSubtitleAppearance(appearance)
        }
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        val args = _uiState.value.launchArgs
        if (durationMs <= 0L) return
        viewModelScope.launch {
            repository.upsertWatchProgress(
                WatchProgress(
                    mediaId = args.mediaId,
                    sourceId = args.sourceId,
                    type = args.type,
                    title = args.title,
                    posterUrl = args.posterUrl,
                    seasonNumber = args.seasonNumber,
                    episodeNumber = args.episodeNumber,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun playNextEpisode() {
        val current = _uiState.value.launchArgs
        val currentEpNum = current.episodeNumber ?: return
        val nextEp = seasonEpisodes.firstOrNull { it.episodeNumber == currentEpNum + 1 }
            ?: seasonEpisodes.dropWhile { it.episodeNumber <= currentEpNum }.firstOrNull()
            ?: return

        val baseTitle = current.title.substringBefore(" • S")
        val nextArgs = current.copy(
            title = "$baseTitle • S${nextEp.seasonNumber}E${nextEp.episodeNumber}",
            posterUrl = nextEp.thumbnailUrl ?: current.posterUrl,
            seasonNumber = nextEp.seasonNumber,
            episodeNumber = nextEp.episodeNumber,
            resumePositionMs = 0L
        )
        loadStreams(nextArgs)
    }

    private fun loadStreams(args: PlayerLaunchArgs) {
        _uiState.value = _uiState.value.copy(
            launchArgs = args,
            streamResult = null,
            isLoading = true,
            error = null
        )

        viewModelScope.launch {
            runCatching {
                val provider = sourceManager.getProvider(args.sourceId)
                    ?: error("Source '${args.sourceId}' not available")

                val seasonNum = args.seasonNumber
                val episodeNum = args.episodeNumber
                if ((args.type == MediaType.TV || args.type == MediaType.ANIME) &&
                    seasonNum != null && seasonEpisodes.isEmpty()
                ) {
                    seasonEpisodes = runCatching {
                        provider.getEpisodes(args.mediaId, seasonNum)
                    }.getOrDefault(emptyList())
                }

                val hasNext = episodeNum != null &&
                    seasonEpisodes.any { it.episodeNumber > episodeNum }

                val result = provider.getStreams(
                    mediaId = args.mediaId,
                    type = args.type,
                    season = args.seasonNumber,
                    episode = args.episodeNumber
                )
                if (result.streams.isEmpty()) {
                    error("No playable streams returned by source.")
                }
                result to hasNext
            }.onSuccess { (result, hasNext) ->
                _uiState.value = _uiState.value.copy(
                    streamResult = result,
                    isLoading = false,
                    error = null,
                    hasNextEpisode = hasNext
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = throwable.message ?: "Failed to load stream"
                )
            }
        }
    }

    class Factory(
        private val initialArgs: PlayerLaunchArgs,
        private val sourceManager: SourceManager,
        private val repository: AtrisRepository,
        private val preferencesRepository: UserPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(initialArgs, sourceManager, repository, preferencesRepository) as T
    }
}
