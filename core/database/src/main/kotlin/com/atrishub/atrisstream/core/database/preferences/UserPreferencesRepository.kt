package com.atrishub.atrisstream.core.database.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.atrishub.atrisstream.core.model.MediaType
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

    val primaryMovieSourceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PRIMARY_MOVIE_SOURCE_ID] ?: DEFAULT_PRIMARY_MOVIE_SOURCE_ID
    }

    val primaryTvSourceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PRIMARY_TV_SOURCE_ID] ?: DEFAULT_PRIMARY_TV_SOURCE_ID
    }

    val primaryAnimeSourceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PRIMARY_ANIME_SOURCE_ID] ?: DEFAULT_PRIMARY_ANIME_SOURCE_ID
    }

    val trackSeriesNewEpisodes: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TRACK_SERIES_NEW_EPISODES] ?: DEFAULT_TRACK_SERIES_NEW_EPISODES
    }

    fun getPrimarySourceIdFlow(type: MediaType): Flow<String> = when (type) {
        MediaType.MOVIE -> primaryMovieSourceId
        MediaType.TV -> primaryTvSourceId
        MediaType.ANIME -> primaryAnimeSourceId
        MediaType.LIVE -> primaryTvSourceId
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

    suspend fun setPrimaryMovieSourceId(sourceId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PRIMARY_MOVIE_SOURCE_ID] = sourceId
        }
    }

    suspend fun setPrimaryTvSourceId(sourceId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PRIMARY_TV_SOURCE_ID] = sourceId
        }
    }

    suspend fun setPrimaryAnimeSourceId(sourceId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PRIMARY_ANIME_SOURCE_ID] = sourceId
        }
    }

    suspend fun setTrackSeriesNewEpisodes(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TRACK_SERIES_NEW_EPISODES] = enabled
        }
    }

    private companion object {
        const val DEFAULT_APP_LANGUAGE = "system"
        const val DEFAULT_PREFERRED_QUALITY = "Auto"
        const val DEFAULT_PREFERRED_SUBTITLE_LANG = "tr"
        const val DEFAULT_PRIMARY_MOVIE_SOURCE_ID = "builtin_movy_sx"
        const val DEFAULT_PRIMARY_TV_SOURCE_ID = "builtin_movy_sx"
        const val DEFAULT_PRIMARY_ANIME_SOURCE_ID = "builtin_reanime"
        const val DEFAULT_TRACK_SERIES_NEW_EPISODES = false

        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val KEY_PREFERRED_SUBTITLE_LANG = stringPreferencesKey("preferred_subtitle_lang")
        val KEY_SUBTITLE_FONT_SIZE_SP = floatPreferencesKey("subtitle_font_size_sp")
        val KEY_SUBTITLE_TEXT_COLOR_ARGB = longPreferencesKey("subtitle_text_color_argb")
        val KEY_SUBTITLE_BG_COLOR_ARGB = longPreferencesKey("subtitle_bg_color_argb")
        val KEY_SUBTITLE_EDGE_TYPE = intPreferencesKey("subtitle_edge_type")
        val KEY_PRIMARY_MOVIE_SOURCE_ID = stringPreferencesKey("primary_movie_source_id")
        val KEY_PRIMARY_TV_SOURCE_ID = stringPreferencesKey("primary_tv_source_id")
        val KEY_PRIMARY_ANIME_SOURCE_ID = stringPreferencesKey("primary_anime_source_id")
        val KEY_TRACK_SERIES_NEW_EPISODES = booleanPreferencesKey("track_series_new_episodes")
    }
}
