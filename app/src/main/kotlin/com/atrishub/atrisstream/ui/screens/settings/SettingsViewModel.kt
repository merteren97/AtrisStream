package com.atrishub.atrisstream.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.atrishub.atrisstream.core.database.preferences.UserPreferencesRepository
import com.atrishub.atrisstream.core.model.SubtitleAppearance
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    val appLanguage: StateFlow<String> = preferencesRepository.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    val preferredQuality: StateFlow<String> = preferencesRepository.preferredQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Auto")

    val preferredSubtitleLang: StateFlow<String> = preferencesRepository.preferredSubtitleLang
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "tr")

    val subtitleAppearance: StateFlow<SubtitleAppearance> = preferencesRepository.subtitleAppearance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleAppearance())

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

    class Factory(private val preferencesRepository: UserPreferencesRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(preferencesRepository) as T
    }
}
