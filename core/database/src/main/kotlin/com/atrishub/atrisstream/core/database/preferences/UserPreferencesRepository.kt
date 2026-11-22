package com.atrishub.atrisstream.core.database.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.atrishub.atrisstream.core.model.SubtitleAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.atrisPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "atris_user_preferences"
)

/**
 * DataStore-backed user preferences for language, playback quality, and subtitle styling.
 */
class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.atrisPreferencesDataStore)

    val appLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_APP_LANGUAGE] ?: DEFAULT_APP_LANGUAGE
    }

    val preferredQuality: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PREFERRED_QUALITY] ?: DEFAULT_PREFERRED_QUALITY
    }

    val preferredSubtitleLang: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PREFERRED_SUBTITLE_LANG] ?: DEFAULT_PREFERRED_SUBTITLE_LANG
    }

    val subtitleAppearance: Flow<SubtitleAppearance> = dataStore.data.map { prefs ->
        val defaults = SubtitleAppearance()
        SubtitleAppearance(
            fontSizeSp = prefs[KEY_SUBTITLE_FONT_SIZE_SP] ?: defaults.fontSizeSp,
            textColorArgb = prefs[KEY_SUBTITLE_TEXT_COLOR_ARGB] ?: defaults.textColorArgb,
            backgroundColorArgb = prefs[KEY_SUBTITLE_BG_COLOR_ARGB] ?: defaults.backgroundColorArgb,
            edgeType = prefs[KEY_SUBTITLE_EDGE_TYPE] ?: defaults.edgeType
        )
    }

    suspend fun setAppLanguage(language: String) {
        dataStore.edit { prefs ->
            prefs[KEY_APP_LANGUAGE] = language
        }
    }

    suspend fun setPreferredQuality(quality: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFERRED_QUALITY] = quality
        }
    }

    suspend fun setPreferredSubtitleLang(language: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFERRED_SUBTITLE_LANG] = language
        }
    }

    suspend fun setSubtitleAppearance(appearance: SubtitleAppearance) {
        dataStore.edit { prefs ->
            prefs[KEY_SUBTITLE_FONT_SIZE_SP] = appearance.fontSizeSp
            prefs[KEY_SUBTITLE_TEXT_COLOR_ARGB] = appearance.textColorArgb
            prefs[KEY_SUBTITLE_BG_COLOR_ARGB] = appearance.backgroundColorArgb
            prefs[KEY_SUBTITLE_EDGE_TYPE] = appearance.edgeType
        }
    }

    private companion object {
        const val DEFAULT_APP_LANGUAGE = "system"
        const val DEFAULT_PREFERRED_QUALITY = "Auto"
        const val DEFAULT_PREFERRED_SUBTITLE_LANG = "tr"

        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val KEY_PREFERRED_SUBTITLE_LANG = stringPreferencesKey("preferred_subtitle_lang")
        val KEY_SUBTITLE_FONT_SIZE_SP = floatPreferencesKey("subtitle_font_size_sp")
        val KEY_SUBTITLE_TEXT_COLOR_ARGB = longPreferencesKey("subtitle_text_color_argb")
        val KEY_SUBTITLE_BG_COLOR_ARGB = longPreferencesKey("subtitle_bg_color_argb")
        val KEY_SUBTITLE_EDGE_TYPE = intPreferencesKey("subtitle_edge_type")
    }
}
