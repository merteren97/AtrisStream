package com.atrishub.atrisstream.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.model.Episode
import com.atrishub.atrisstream.core.model.MediaDetails
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.model.WatchlistItem
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailsUiState(
    val details: MediaDetails? = null,
    val selectedSeasonNumber: Int = 1,
    val episodes: List<Episode> = emptyList(),
    val isLoadingEpisodes: Boolean = false,
    val latestProgress: WatchProgress? = null,
    val episodeProgressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class DetailsViewModel(
    private val sourceId: String,
    private val mediaId: String,
    private val mediaType: MediaType,
    private val sourceManager: SourceManager,
    private val repository: AtrisRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    val isSavedToWatchlist: StateFlow<Boolean> = repository.observeIsSaved(sourceId, mediaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        loadDetails()
    }

    fun loadDetails() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val provider = sourceManager.getProvider(sourceId)
                    ?: error("Source '$sourceId' not found or disabled")
                val details = provider.getDetails(mediaId, mediaType)
                val latestProgress = repository.getLatestProgressForMedia(sourceId, mediaId)
                val episodeProgressMap = repository.getAllProgressForMedia(sourceId, mediaId)
                    .associateBy { (it.seasonNumber ?: -1) to (it.episodeNumber ?: -1) }
                Triple(details, latestProgress, episodeProgressMap)
            }.onSuccess { (details, latestProgress, episodeProgressMap) ->
                val targetSeason = latestProgress?.seasonNumber?.let { s ->
                    details.seasons.firstOrNull { it.seasonNumber == s }
                } ?: details.seasons.firstOrNull()
                val initialSeasonNum = targetSeason?.seasonNumber ?: 1
                val initialEpisodes = targetSeason?.episodes.orEmpty()

                _uiState.value = _uiState.value.copy(
                    details = details,
                    selectedSeasonNumber = initialSeasonNum,
                    episodes = initialEpisodes,
                    latestProgress = latestProgress,
                    episodeProgressMap = episodeProgressMap,
                    isLoading = false,
                    error = null
                )

                if ((details.type == MediaType.TV || details.type == MediaType.ANIME) &&
                    initialEpisodes.isEmpty() && details.seasons.isNotEmpty()
                ) {
                    loadEpisodesForSeason(initialSeasonNum)
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = throwable.message ?: "Failed to load details"
                )
            }
        }
    }

    fun refreshWatchProgress() {
        viewModelScope.launch {
            runCatching {
                val latestProgress = repository.getLatestProgressForMedia(sourceId, mediaId)
                val episodeProgressMap = repository.getAllProgressForMedia(sourceId, mediaId)
                    .associateBy { (it.seasonNumber ?: -1) to (it.episodeNumber ?: -1) }
                latestProgress to episodeProgressMap
            }.onSuccess { (latestProgress, episodeProgressMap) ->
                _uiState.value = _uiState.value.copy(
                    latestProgress = latestProgress,
                    episodeProgressMap = episodeProgressMap
                )
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        val details = _uiState.value.details ?: return
        val seasonObj = details.seasons.firstOrNull { it.seasonNumber == seasonNumber }
        _uiState.value = _uiState.value.copy(selectedSeasonNumber = seasonNumber)

        if (seasonObj != null && seasonObj.episodes.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(episodes = seasonObj.episodes)
        } else {
            loadEpisodesForSeason(seasonNumber)
        }
    }

    private fun loadEpisodesForSeason(seasonNumber: Int) {
        _uiState.value = _uiState.value.copy(isLoadingEpisodes = true)
        viewModelScope.launch {
            runCatching {
                val provider = sourceManager.getProvider(sourceId)
                    ?: error("Source '$sourceId' unavailable")
                provider.getEpisodes(mediaId, seasonNumber)
            }.onSuccess { episodes ->
                _uiState.value = _uiState.value.copy(
                    episodes = episodes,
                    isLoadingEpisodes = false
                )
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.value = _uiState.value.copy(
                    episodes = emptyList(),
                    isLoadingEpisodes = false
                )
            }
        }
    }

    fun toggleWatchlist() {
        val details = _uiState.value.details ?: return
        viewModelScope.launch {
            if (isSavedToWatchlist.value) {
                repository.removeFromWatchlist(sourceId, mediaId)
            } else {
                repository.upsertWatchlistItem(
                    WatchlistItem(
                        mediaId = details.id,
                        sourceId = details.sourceId,
                        type = details.type,
                        title = details.title,
                        posterUrl = details.posterUrl,
                        year = details.year,
                        rating = details.rating,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    class Factory(
        private val sourceId: String,
        private val mediaId: String,
        private val mediaType: MediaType,
        private val sourceManager: SourceManager,
        private val repository: AtrisRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailsViewModel(sourceId, mediaId, mediaType, sourceManager, repository) as T
    }
}
