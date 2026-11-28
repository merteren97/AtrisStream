package com.atrishub.atrisstream.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.database.preferences.UserPreferencesRepository
import com.atrishub.atrisstream.core.model.InstalledSource
import com.atrishub.atrisstream.core.model.SubtitleAppearance
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val sourceManager: SourceManager
) : ViewModel() {

    val enabledSources: StateFlow<List<InstalledSource>> = sourceManager.observeSources()
        .map { list -> list.filter { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val appLanguage: StateFlow<String> = preferencesRepository.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    val preferredQuality: StateFlow<String> = preferencesRepository.preferredQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Auto")

    val preferredSubtitleLang: StateFlow<String> = preferencesRepository.preferredSubtitleLang
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "tr")

    val subtitleAppearance: StateFlow<SubtitleAppearance> = preferencesRepository.subtitleAppearance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleAppearance())

    val primaryMovieSourceId: StateFlow<String> = preferencesRepository.primaryMovieSourceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "builtin_movy_sx")

    val primaryTvSourceId: StateFlow<String> = preferencesRepository.primaryTvSourceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "builtin_movy_sx")

    val primaryAnimeSourceId: StateFlow<String> = preferencesRepository.primaryAnimeSourceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "builtin_reanime")

    val trackSeriesNewEpisodes: StateFlow<Boolean> = preferencesRepository.trackSeriesNewEpisodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAppLanguage(lang: String) {
        viewModelScope.launch { preferencesRepository.setAppLanguage(lang) }
    }

    fun setPreferredQuality(quality: String) {
        viewModelScope.launch { preferencesRepository.setPreferredQuality(quality) }
    }

    fun setPreferredSubtitleLang(lang: String) {
        viewModelScope.launch { preferencesRepository.setPreferredSubtitleLang(lang) }
    }

    fun updateSubtitleAppearance(appearance: SubtitleAppearance) {
        viewModelScope.launch { preferencesRepository.setSubtitleAppearance(appearance) }
    }

    fun setPrimaryMovieSourceId(sourceId: String) {
        viewModelScope.launch { preferencesRepository.setPrimaryMovieSourceId(sourceId) }
    }

    fun setPrimaryTvSourceId(sourceId: String) {
        viewModelScope.launch { preferencesRepository.setPrimaryTvSourceId(sourceId) }
    }

    fun setPrimaryAnimeSourceId(sourceId: String) {
        viewModelScope.launch { preferencesRepository.setPrimaryAnimeSourceId(sourceId) }
    }

    fun setTrackSeriesNewEpisodes(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setTrackSeriesNewEpisodes(enabled) }
    }

    class Factory(
        private val preferencesRepository: UserPreferencesRepository,
        private val sourceManager: SourceManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(preferencesRepository, sourceManager) as T
    }
}
