package com.atrishub.atrisstream.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.database.preferences.UserPreferencesRepository
import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.model.HomeSection
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaProvider
import com.atrishub.atrisstream.core.model.WatchProgress
import com.atrishub.atrisstream.core.model.WatchlistItem
import com.atrishub.atrisstream.core.model.WatchlistTrackingPolicy
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val providers: List<MediaProvider> = emptyList(),
    val selectedSourceId: String? = null,
    val sections: List<HomeSection> = emptyList(),
    val featuredItem: MediaCard? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

class HomeViewModel(
    private val sourceManager: SourceManager,
    private val repository: AtrisRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val continueWatching: StateFlow<List<WatchProgress>> = repository.observeContinueWatching(limit = 16)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val newEpisodeWatchlistItems: StateFlow<List<WatchlistItem>> = repository.observeWatchlist()
        .map { items ->
            WatchlistTrackingPolicy.sortWatchlistWithPriority(items.filter { it.hasNewEpisode })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var lastWatchlistRefreshMs: Long = 0L

    init {
        viewModelScope.launch {
            sourceManager.observeEnabledProviders().collectLatest { providers ->
                val currentSelected = _uiState.value.selectedSourceId
                val nextSelected = providers.firstOrNull { it.manifest.id == currentSelected }?.manifest?.id
                    ?: providers.firstOrNull()?.manifest?.id

                _uiState.value = _uiState.value.copy(
                    providers = providers,
                    selectedSourceId = nextSelected
                )
                loadHomeForSelected(providers, nextSelected)
            }
        }
        refreshWatchlistIfStale()
    }

    fun refreshWatchlistIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastWatchlistRefreshMs < WATCHLIST_REFRESH_THROTTLE_MS) return
        lastWatchlistRefreshMs = now
        viewModelScope.launch {
            runCatching {
                val trackSeries = preferencesRepository.trackSeriesNewEpisodes.first()
                sourceManager.refreshWatchlistNewEpisodes(trackSeries)
            }
        }
    }

    fun selectSource(sourceId: String) {
        if (_uiState.value.selectedSourceId == sourceId) return
        _uiState.value = _uiState.value.copy(selectedSourceId = sourceId)
        viewModelScope.launch {
            loadHomeForSelected(_uiState.value.providers, sourceId)
        }
    }

    fun retry() {
        viewModelScope.launch {
            loadHomeForSelected(_uiState.value.providers, _uiState.value.selectedSourceId)
        }
    }

    fun removeFromContinueWatching(progress: WatchProgress) {
        viewModelScope.launch {
            repository.deleteWatchProgress(
                sourceId = progress.sourceId,
                mediaId = progress.mediaId,
                seasonNumber = progress.seasonNumber,
                episodeNumber = progress.episodeNumber
            )
        }
    }

    fun markAsWatched(progress: WatchProgress) {
        viewModelScope.launch {
            repository.markWatchProgressCompleted(
                sourceId = progress.sourceId,
                mediaId = progress.mediaId,
                seasonNumber = progress.seasonNumber,
                episodeNumber = progress.episodeNumber
            )
        }
    }

    private suspend fun loadHomeForSelected(
        providers: List<MediaProvider>,
        sourceId: String?
    ) {
        val provider = providers.firstOrNull { it.manifest.id == sourceId }
        if (provider == null) {
            _uiState.value = _uiState.value.copy(
                sections = emptyList(),
                featuredItem = null,
                isLoading = false,
                error = null
            )
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        runCatching {
            provider.getHome()
        }.onSuccess { sections ->
            val featured = sections.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
            _uiState.value = _uiState.value.copy(
                sections = sections,
                featuredItem = featured,
                isLoading = false,
                error = null
            )
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = throwable.message ?: "Failed to load home feed"
            )
        }
    }

    companion object {
        private const val WATCHLIST_REFRESH_THROTTLE_MS = 15 * 60 * 1000L
    }

    class Factory(
        private val sourceManager: SourceManager,
        private val repository: AtrisRepository,
        private val preferencesRepository: UserPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(sourceManager, repository, preferencesRepository) as T
    }
}
