package com.atrishub.atrisstream.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Creates a localized [Context] wrapper for live in-app language switching
 * supporting "system", "en", and "tr".
 */
object LocaleHelper {
    fun localizedContext(base: Context, languageCode: String): Context {
        if (languageCode == "system" || languageCode.isBlank()) {
            return base
        }
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
