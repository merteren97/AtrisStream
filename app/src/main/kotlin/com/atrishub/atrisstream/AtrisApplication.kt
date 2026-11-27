package com.atrishub.atrisstream

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.atrishub.atrisstream.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Application class initializing [AppContainer], Coil [ImageLoader], and built-in sources.
 */
class AtrisApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.sourceManager.initialize()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized &&
            (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        ) {
            container.imageLoader.memoryCache?.clear()
            container.networkClient.baseClient.connectionPool.evictAll()
            container.sourceManager.trimMemory()
        }
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
