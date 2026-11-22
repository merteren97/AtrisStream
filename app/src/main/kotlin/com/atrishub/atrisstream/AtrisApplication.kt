package com.atrishub.atrisstream

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.atrishub.atrisstream.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class initializing [AppContainer], Coil [ImageLoader], and built-in sources.
 */
class AtrisApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            container.sourceManager.initialize()
        }
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
