package com.atrishub.atrisstream.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.database.preferences.UserPreferencesRepository
import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.model.WatchlistItem
import com.atrishub.atrisstream.core.model.WatchlistTrackingPolicy
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WatchlistRefreshUiState(
    val isRefreshing: Boolean = false,
    val lastRefreshedAt: Long? = null,
    val lastNewEpisodesFound: Int? = null
)

class WatchlistViewModel(
    private val repository: AtrisRepository,
    private val sourceManager: SourceManager,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    val items: StateFlow<List<WatchlistItem>> = repository.observeWatchlist()
        .map { WatchlistTrackingPolicy.sortWatchlistWithPriority(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshState = MutableStateFlow(WatchlistRefreshUiState())
    val refreshState: StateFlow<WatchlistRefreshUiState> = _refreshState.asStateFlow()

    fun refreshNewEpisodes() {
        if (_refreshState.value.isRefreshing) return
        _refreshState.value = _refreshState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            val found = runCatching {
                val trackSeries = preferencesRepository.trackSeriesNewEpisodes.first()
                sourceManager.refreshWatchlistNewEpisodes(trackSeries)
            }.getOrNull()
            _refreshState.value = WatchlistRefreshUiState(
                isRefreshing = false,
                lastRefreshedAt = System.currentTimeMillis(),
                lastNewEpisodesFound = found
            )
        }
    }

    fun remove(item: WatchlistItem) {
        viewModelScope.launch {
            repository.removeFromWatchlist(item.sourceId, item.mediaId)
        }
    }

    class Factory(
        private val repository: AtrisRepository,
        private val sourceManager: SourceManager,
        private val preferencesRepository: UserPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WatchlistViewModel(repository, sourceManager, preferencesRepository) as T
    }
}
