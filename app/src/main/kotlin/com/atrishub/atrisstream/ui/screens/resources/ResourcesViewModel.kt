package com.atrishub.atrisstream.ui.screens.resources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.sourceengine.SourceHealthResult
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import com.atrishub.atrisstream.server.LanPasteServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ResourcesUiState(
    val isAdding: Boolean = false,
    val addFeedback: String? = null,
    val addError: String? = null,
    val testingSourceIds: Set<String> = emptySet(),
    val healthResults: Map<String, SourceHealthResult> = emptyMap()
)

class ResourcesViewModel(
    private val sourceManager: SourceManager,
    private val lanPasteServer: LanPasteServer
) : ViewModel() {

    val sources: StateFlow<List<InstalledSource>> = sourceManager.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isLanServerRunning: StateFlow<Boolean> = lanPasteServer.isRunning
    val lanServerUrl: StateFlow<String?> = lanPasteServer.serverUrl
    val lanLastStatus: StateFlow<String?> = lanPasteServer.lastStatusMessage

    private val _uiState = MutableStateFlow(ResourcesUiState())
    val uiState: StateFlow<ResourcesUiState> = _uiState.asStateFlow()

    fun toggleLanServer() {
        if (lanPasteServer.isRunning.value) {
            lanPasteServer.stop()
        } else {
            lanPasteServer.start()
        }
    }

    fun addSourceUrl(url: String, onSuccess: () -> Unit) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        _uiState.value = _uiState.value.copy(isAdding = true, addFeedback = null, addError = null)
        viewModelScope.launch {
            sourceManager.addSourceFromUrl(trimmed).fold(
                onSuccess = { added ->
                    _uiState.value = _uiState.value.copy(
                        isAdding = false,
                        addFeedback = "Added ${added.size} source(s)",
                        addError = null
                    )
                    onSuccess()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isAdding = false,
                        addError = err.message ?: "Failed to add source"
                    )
                }
            )
        }
    }

    fun clearAddMessages() {
        _uiState.value = _uiState.value.copy(addFeedback = null, addError = null)
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            sourceManager.setSourceEnabled(id, enabled)
        }
    }

    fun removeSource(id: String) {
        viewModelScope.launch {
            sourceManager.removeSource(id)
        }
    }

    fun testSource(id: String) {
        if (id in _uiState.value.testingSourceIds) return
        _uiState.value = _uiState.value.copy(
            testingSourceIds = _uiState.value.testingSourceIds + id
        )
        viewModelScope.launch {
            val result = sourceManager.testSource(id)
            _uiState.value = _uiState.value.copy(
                testingSourceIds = _uiState.value.testingSourceIds - id,
                healthResults = _uiState.value.healthResults + (id to result)
            )
        }
    }

    class Factory(
        private val sourceManager: SourceManager,
        private val lanPasteServer: LanPasteServer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ResourcesViewModel(sourceManager, lanPasteServer) as T
    }
}
