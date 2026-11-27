package com.atrishub.atrisstream.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SearchResultGroup(
    val sourceId: String,
    val sourceName: String,
    val items: List<MediaCard>
)

data class SearchUiState(
    val query: String = "",
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,
    val groups: List<SearchResultGroup> = emptyList(),
    val error: String? = null
)

class SearchViewModel(
    private val sourceManager: SourceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
    }

    fun search(overrideQuery: String? = null) {
        val activeQuery = (overrideQuery ?: _uiState.value.query).trim()
        if (activeQuery.isBlank()) return

        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            query = activeQuery,
            hasSearched = true,
            isSearching = true,
            error = null
        )

        searchJob = viewModelScope.launch {
            runCatching {
                val providers = sourceManager.observeEnabledProviders().first()
                coroutineScope {
                    providers.map { provider ->
                        async {
                            val result = runCatching { provider.search(activeQuery, page = 1) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()
                            SearchResultGroup(
                                sourceId = provider.manifest.id,
                                sourceName = provider.manifest.name,
                                items = result?.items.orEmpty()
                                    .distinctBy { "${it.sourceId}_${it.type.name}_${it.id}" }
                            )
                        }
                    }.awaitAll()
                }.filter { it.items.isNotEmpty() }
            }.onSuccess { groups ->
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    groups = groups,
                    error = null
                )
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = throwable.message ?: "Search failed"
                )
            }
        }
    }

    class Factory(private val sourceManager: SourceManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(sourceManager) as T
    }
}
