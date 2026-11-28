package com.atrishub.atrisstream.ui.screens.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.database.preferences.UserPreferencesRepository
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaProvider
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class BrowseUiState(
    val mediaType: MediaType = MediaType.MOVIE,
    val providers: List<MediaProvider> = emptyList(),
    val selectedSourceId: String? = null,
    val items: List<MediaCard> = emptyList(),
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null
)

class BrowseViewModel(
    private val mediaType: MediaType,
    private val observeProviders: () -> Flow<List<MediaProvider>>,
    private val observePrimarySourceId: () -> Flow<String> = { flowOf("") }
) : ViewModel() {

    constructor(
        mediaType: MediaType,
        sourceManager: SourceManager,
        preferencesRepository: UserPreferencesRepository
    ) : this(
        mediaType = mediaType,
        observeProviders = sourceManager::observeEnabledProviders,
        observePrimarySourceId = { preferencesRepository.getPrimarySourceIdFlow(mediaType) }
    )

    private val _uiState = MutableStateFlow(BrowseUiState(mediaType = mediaType))
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private var userSelectedSourceId: String? = null

    init {
        viewModelScope.launch {
            combine(observeProviders(), observePrimarySourceId()) { allProviders, primaryId ->
                allProviders to primaryId
            }.collectLatest { (allProviders, primaryId) ->
                val candidates = allProviders.filter { mediaType in it.manifest.supportedTypes }
                val nextId = candidates.firstOrNull { it.manifest.id == userSelectedSourceId }?.manifest?.id
                    ?: candidates.firstOrNull { it.manifest.id == primaryId }?.manifest?.id
                    ?: candidates.firstOrNull()?.manifest?.id
                _uiState.value = _uiState.value.copy(
                    providers = candidates,
                    selectedSourceId = nextId
                )
                loadPage(candidates, nextId, page = 1, append = false)
            }
        }
    }

    fun selectSource(sourceId: String) {
        userSelectedSourceId = sourceId
        if (_uiState.value.selectedSourceId == sourceId) return
        _uiState.value = _uiState.value.copy(selectedSourceId = sourceId)
        viewModelScope.launch {
            loadPage(_uiState.value.providers, sourceId, page = 1, append = false)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasNextPage) return
        viewModelScope.launch {
            loadPage(state.providers, state.selectedSourceId, page = state.currentPage + 1, append = true)
        }
    }

    fun retry() {
        val state = _uiState.value
        viewModelScope.launch {
            loadPage(state.providers, state.selectedSourceId, page = 1, append = false)
        }
    }

    private suspend fun loadPage(
        providers: List<MediaProvider>,
        sourceId: String?,
        page: Int,
        append: Boolean
    ) {
        val provider = providers.firstOrNull { it.manifest.id == sourceId }
        if (provider == null) {
            _uiState.value = _uiState.value.copy(
                items = emptyList(),
                isLoading = false,
                isLoadingMore = false,
                hasNextPage = false,
                error = null
            )
            return
        }

        _uiState.value = if (append) {
            _uiState.value.copy(isLoadingMore = true, error = null)
        } else {
            _uiState.value.copy(isLoading = true, items = emptyList(), error = null)
        }

        runCatching {
            provider.browse(mediaType, page)
        }.onSuccess { paged ->
            val combined = if (append) {
                (_uiState.value.items + paged.items)
                    .distinctBy { "${it.sourceId}_${it.type.name}_${it.id}" }
                    .take(MAX_BROWSE_ITEMS)
            } else {
                paged.items
                    .distinctBy { "${it.sourceId}_${it.type.name}_${it.id}" }
                    .take(MAX_BROWSE_ITEMS)
            }
            _uiState.value = _uiState.value.copy(
                items = combined,
                currentPage = paged.page,
                hasNextPage = paged.hasNextPage && combined.size < MAX_BROWSE_ITEMS,
                isLoading = false,
                isLoadingMore = false,
                error = null
            )
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isLoadingMore = false,
                error = throwable.message ?: "Failed to load catalog"
            )
        }
    }

    companion object {
        const val MAX_BROWSE_ITEMS = 200
    }

    class Factory(
        private val mediaType: MediaType,
        private val sourceManager: SourceManager,
        private val preferencesRepository: UserPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowseViewModel(mediaType, sourceManager, preferencesRepository) as T
    }
}
