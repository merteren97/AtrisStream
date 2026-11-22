package com.atrishub.atrisstream.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.model.WatchlistItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WatchlistViewModel(
    private val repository: AtrisRepository
) : ViewModel() {

    val items: StateFlow<List<WatchlistItem>> = repository.observeWatchlist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(item: WatchlistItem) {
        viewModelScope.launch {
            repository.removeFromWatchlist(item.sourceId, item.mediaId)
        }
    }

    class Factory(private val repository: AtrisRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WatchlistViewModel(repository) as T
    }
}
