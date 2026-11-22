package com.atrishub.atrisstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.atrishub.atrisstream.ui.AtrisApp
import com.atrishub.atrisstream.ui.theme.AtrisStreamTheme
import com.atrishub.atrisstream.util.LocaleHelper

/**
 * Main entry point for AtrisStream on Android TV / Google TV.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AtrisApplication).container

        setContent {
            val appLanguage by container.preferencesRepository.appLanguage.collectAsState(initial = "system")
            val localizedContext = remember(appLanguage, this) {
                LocaleHelper.localizedContext(this, appLanguage)
            }

            CompositionLocalProvider(LocalContext provides localizedContext) {
                AtrisStreamTheme {
                    AtrisApp(container = container)
                }
            }
        }
    }
}
